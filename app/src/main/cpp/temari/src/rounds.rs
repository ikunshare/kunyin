//! Core decryption: template struct + CBC decrypt_sample loop.
//! round1/2/3 chain lives in rounds_gen (transpiled from the Python ports).

use crate::rounds_gen::*;

pub use crate::rounds_gen::ST_SIZE;
pub use crate::rounds_gen::St;

pub const CTX_SIZE: usize = 0x8000;

#[derive(Clone, Copy)]
pub struct R1Entry {
    pub rdx: u32,
    pub rcx: u32,
    pub rax: u32,
    pub r9: u32,
    pub rbp: u32,
}

#[derive(Clone)]
pub struct Template {
    pub ctx: Vec<u8>,
    pub st_init: St,
    pub r1_entry: R1Entry,
}

impl Template {
    pub fn new(ctx: Vec<u8>, st_init: St, r1_entry: R1Entry) -> Template {
        Template { ctx, st_init, r1_entry }
    }
}

/// Decrypt the aligned prefix into a caller-supplied reusable buffer.
pub(crate) fn decrypt_sample_into(tmpl: &Template, ciphertext: &[u8], out: &mut Vec<u8>) {
    let nblocks = ciphertext.len() / 16;
    out.clear();
    if nblocks == 0 {
        return;
    }
    out.resize(nblocks * 16, 0);
    decrypt_blocks_into(tmpl, ciphertext, out);
}

/// Process one 16-byte block: R1→R2→R3→CBC, write the plaintext into `out16`.
/// `#[inline(always)]` so callers can interleave two independent sample chains
/// in one loop (2-lane ILP) for higher per-core throughput.
#[inline(always)]
fn process_block(tmpl: &Template, st: &mut St, ct: &[u8], bi: usize, out16: &mut [u8]) {
    let rdi = 0x1EB2C6B4u32 ^ ((bi as u32) << 4);
    let rsi = 0x8u32 + ((bi as u32) << 4);
    let regs = Round1Regs {
        rdi,
        rsi,
        rdx: tmpl.r1_entry.rdx,
        rcx: tmpl.r1_entry.rcx,
        r8: 0,
        r9: tmpl.r1_entry.r9,
        rax: tmpl.r1_entry.rax,
        rbx: 0,
        r10: 0,
        r11: 0,
        r12: 0,
        r13: 0,
        r14: 0,
        r15: 0,
        rbp: tmpl.r1_entry.rbp,
    };
    let ctx = &tmpl.ctx;

    let mid = round1_mid(ctx, st, ct, &regs);
    let r2 = round1_tail(
        ctx,
        st,
        mid.rax,
        mid.r13 & 0xFF,
        mid.r15 & 0xFF,
        mid.r8 & 0xFF,
        mid.r14 & 0xFF,
    );
    let r2v = round2_sub6400(
        ctx, st, r2.rdi, r2.rsi, r2.rdx, r2.rcx, r2.r8, r2.r9, r2.rax, r2.rbx,
        r2.r10, r2.r11, r2.r13, r2.r14, r2.r15, 0,
    );

    // R3 boundary parameters (from decrypt_tool.decrypt_sample)
    // cp12 is now [st[0x48], st[0x250], st[0x290]] snapshot: [0]=0x48, [1]=0x250, [2]=0x290
    let cp12 = &r2v.cp12;
    let r8p = cp12[2] ^ st[0x5B8] ^ cp12[1];
    let v6 = t4(ctx, 0x46a0, st[0x390] ^ 0x2B)
        ^ st[0x5F0]
        ^ t4(ctx, 0x4ac0, ((r8p >> 24) & 0xFF) ^ 0x29)
        ^ t4(ctx, 0x2ff0, ((st[0x298] >> 16) & 0xFF) ^ 0xD6);
    let v9 = t4(ctx, 0x4ac0, cp12[0])
        ^ st[0x540]
        ^ t4(ctx, 0x2ff0, ((r2v.v171 >> 16) & 0xFF) ^ 0x69);
    let v11 = t4(ctx, 0x3950, (st[0x298] & 0xFF) ^ 0x57)
        ^ st[0x538]
        ^ t4(ctx, 0x46a0, ((r8p >> 8) & 0xFF) ^ 0x2F);

    let a2 = st[0x270];
    let a6 = st[0x280];
    let r3v = round3_sub8000(
        ctx,
        st,
        a2,
        r2v.v189,
        r8p & 0xFF,
        (r8p >> 16) & 0xFFFF,
        a6,
        v6,
        (r8p >> 16) & 0xFF,
        v9,
        r2v.v187 & 0xFF,
        v11,
        r2v.v189,
    );

    reconstruct_block(&r3v.pt, out16);

    // CBC state pass-through
    st[0x108] = st[0x180];
    st[0x220] = st[0x220].wrapping_add(0x10);
}

/// Decrypt the 16-byte-aligned prefix of `ciphertext` into a pre-sized slice.
/// `out.len()` must be >= `ciphertext.len() / 16 * 16`.
fn decrypt_blocks_into(tmpl: &Template, ciphertext: &[u8], out: &mut [u8]) {
    let nblocks = ciphertext.len() / 16;
    let mut st = tmpl.st_init;
    for bi in 0..nblocks {
        process_block(tmpl, &mut st, ciphertext, bi, &mut out[bi * 16..bi * 16 + 16]);
    }
}

/// 2-lane interleaved variant: process two independent samples' blocks in one
/// loop so the CPU overlaps both round chains (ILP). Each worker in the pool
/// handles a pair this way, raising per-core throughput on CPUs that are not
/// already issue-port saturated.
fn decrypt_blocks_pair_into(
    tmpl: &Template,
    ct1: &[u8],
    ct2: &[u8],
    out1: &mut [u8],
    out2: &mut [u8],
) {
    let n1 = ct1.len() / 16;
    let n2 = ct2.len() / 16;
    let n = n1.max(n2);
    let mut st1 = tmpl.st_init;
    let mut st2 = tmpl.st_init;
    for bi in 0..n {
        if bi < n1 {
            process_block(tmpl, &mut st1, ct1, bi, &mut out1[bi * 16..bi * 16 + 16]);
        }
        if bi < n2 {
            process_block(tmpl, &mut st2, ct2, bi, &mut out2[bi * 16..bi * 16 + 16]);
        }
    }
}

/// Decrypt a pair of samples into pre-sized output regions (2-lane interleave).
/// `out1.len() == s1.len()`, `out2.len() == s2.len()`. Tail passes through.
pub(crate) fn decrypt_region_pair_into(
    tmpl: &Template,
    s1: &[u8],
    s2: &[u8],
    out1: &mut [u8],
    out2: &mut [u8],
) {
    let h1 = s1.len() / 16 * 16;
    let h2 = s2.len() / 16 * 16;
    if h1 > 0 || h2 > 0 {
        decrypt_blocks_pair_into(tmpl, &s1[..h1], &s2[..h2], &mut out1[..h1], &mut out2[..h2]);
    }
    out1[h1..].copy_from_slice(&s1[h1..]);
    out2[h2..].copy_from_slice(&s2[h2..]);
}

/// Decrypt one sample into a pre-sized output region (`out.len() == sample.len()`):
/// aligned prefix is decrypted, tail passes through. No allocation.
pub(crate) fn decrypt_region_into(tmpl: &Template, sample: &[u8], out: &mut [u8]) {
    debug_assert_eq!(out.len(), sample.len());
    let head = sample.len() / 16 * 16;
    if head > 0 {
        decrypt_blocks_into(tmpl, &sample[..head], &mut out[..head]);
    }
    out[head..].copy_from_slice(&sample[head..]);
}

/// Reconstruct a 16-byte plaintext block from (offset, byte) pairs.
fn reconstruct_block(pt: &[(u32, u8)], blk: &mut [u8]) {
    let mut base = pt[0].0;
    for &(o, _) in pt.iter() {
        if o < base {
            base = o;
        }
    }
    for &(o, b) in pt.iter() {
        let idx = (o - base) as usize;
        if idx < 16 {
            blk[idx] = b;
        }
    }
}


/// Decrypt one sample. The 16-byte-aligned prefix is decrypted and a
/// non-16-aligned tail passes through unchanged (matching decrypt_tool.main /
/// decrypt_song.py). This is the single-sample public API.
pub fn decrypt(tmpl: &Template, sample: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    decrypt_into(tmpl, sample, &mut out);
    out
}

/// `decrypt` into a caller-supplied reusable buffer (no per-sample allocation).
pub(crate) fn decrypt_into(tmpl: &Template, sample: &[u8], out: &mut Vec<u8>) {
    let head_len = sample.len() / 16 * 16;
    decrypt_sample_into(tmpl, &sample[..head_len], out);
    out.extend_from_slice(&sample[head_len..]);
}


/// Parallel batch decryption: samples are independent (SAMPLE-AES resets per
/// sample), so a whole sample stream decrypts across all cores.
/// Order is preserved (indexed result buffer). Uses a persistent worker pool
/// (no per-call thread creation). Each sample's plaintext is written at the
/// offset given by `offs[i]` into `out`; `out` must be pre-sized to the total.
pub(crate) fn decrypt_par_into(tmpl: &Template, samples: &[&[u8]], offs: &[usize], out: &mut [u8]) {
    crate::pool::par_decrypt_into(tmpl, samples, offs, out);
}

/// Decrypt a batch of independent samples in parallel, preserving order.
/// Each sample is an independent SAMPLE-AES unit (state resets per sample).
pub fn decrypt_par(tmpl: &Template, samples: &[&[u8]]) -> Vec<Vec<u8>> {
    let n = samples.len();
    if n == 0 {
        return Vec::new();
    }
    let mut offs = Vec::with_capacity(n);
    let mut total = 0usize;
    for s in samples.iter() {
        offs.push(total);
        total += s.len();
    }
    let mut out = vec![0u8; total];
    decrypt_par_into(tmpl, samples, &offs, &mut out);
    offs.push(total);
    (0..n).map(|i| out[offs[i]..offs[i + 1]].to_vec()).collect()
}
