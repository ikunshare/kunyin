//! FFI (cdylib) exports for in-process decryption from Python (ctypes) / Go (purego).
//!
//! Template is passed as an opaque heap handle; samples as flat byte buffers.
//! All functions are panic-safe (catch_unwind) so a bug can never unwind across
//! the FFI boundary.
//!
//! Construction is JSON-only (`tmpl_from_json`): the caller fetches the
//! 40020-style key-server JSON themselves (this library performs **no network
//! requests**) and passes the response body here. See
//! `template::template_from_json` for the expected fields.

use crate::rounds::Template;
use crate::stream::StreamDecryptor;
use crate::template::template_from_json;
use std::ffi::{c_char, c_void};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;
use std::sync::Arc;

/// Build a Template handle from a 40020-style JSON response body (UTF-8 bytes).
/// Returns a non-null opaque pointer on success, NULL on any error.
#[no_mangle]
pub extern "C" fn tmpl_from_json(ptr: *const c_char, len: usize) -> *mut c_void {
    let res = catch_unwind(AssertUnwindSafe(|| {
        if ptr.is_null() {
            return std::ptr::null_mut();
        }
        let data = unsafe { slice::from_raw_parts(ptr as *const u8, len) };
        let text = match std::str::from_utf8(data) {
            Ok(t) => t,
            Err(_) => return std::ptr::null_mut(),
        };
        match template_from_json(text) {
            Ok(t) => Box::into_raw(Box::new(t)) as *mut c_void,
            Err(_) => std::ptr::null_mut(),
        }
    }));
    res.unwrap_or(std::ptr::null_mut())
}

/// Decrypt a batch of samples given as SCATTERED pointers (no join needed).
/// `ptrs[i]` points to sample i; `lens[i]` is its length; `out` must be
/// writable for sum(lens). Each plaintext is written at the prefix offset.
/// Returns total bytes written, or 0 on error.
#[no_mangle]
pub extern "C" fn decrypt_samples_par(
    tmpl: *const c_void,
    ptrs: *const *const u8,
    lens: *const usize,
    n: usize,
    out: *mut u8,
) -> usize {
    let res = catch_unwind(AssertUnwindSafe(|| {
        if n == 0 {
            return 0usize;
        }
        if tmpl.is_null() || ptrs.is_null() || lens.is_null() || out.is_null() {
            return 0usize;
        }
        let tmpl = unsafe { &*(tmpl as *const Template) };
        let lens = unsafe { slice::from_raw_parts(lens, n) };
        let ptrs = unsafe { slice::from_raw_parts(ptrs, n) };

        let mut offsets = Vec::with_capacity(n);
        let mut total = 0usize;
        for &l in lens {
            offsets.push(total);
            total += l;
        }
        let refs: Vec<&[u8]> = (0..n)
            .map(|i| unsafe { slice::from_raw_parts(ptrs[i], lens[i]) })
            .collect();
        let out_slice = unsafe { slice::from_raw_parts_mut(out, total) };
        crate::rounds::decrypt_par_into(tmpl, &refs, &offsets, out_slice);
        total
    }));
    res.unwrap_or(0)
}

/// Free a Template handle previously returned by tmpl_from_json.
/// NULL is a no-op.
#[no_mangle]
pub extern "C" fn tmpl_destroy(tmpl: *mut c_void) {
    if tmpl.is_null() {
        return;
    }
    // destroy the Box (drops ctx Vec + st array)
    unsafe { drop(Box::from_raw(tmpl as *mut Template)) };
}

/// Decrypt one sample into a caller-supplied `out` buffer.
/// `out` must be writable for at least `sample_len` bytes.
/// Returns the number of plaintext bytes written (always == sample_len),
/// or 0 on error (null handle / null pointer).
#[no_mangle]
pub extern "C" fn decrypt_sample_ffi(
    tmpl: *const c_void,
    sample: *const u8,
    sample_len: usize,
    out: *mut u8,
) -> usize {
    let res = catch_unwind(AssertUnwindSafe(|| {
        if tmpl.is_null() || sample.is_null() || (sample_len > 0 && out.is_null()) {
            return 0usize;
        }
        let tmpl = unsafe { &*(tmpl as *const Template) };
        let s = unsafe { slice::from_raw_parts(sample, sample_len) };
        let o = unsafe { slice::from_raw_parts_mut(out, sample_len) };
        let plain = crate::rounds::decrypt(tmpl, s);
        o.copy_from_slice(&plain);
        plain.len()
    }));
    res.unwrap_or(0)
}

// ---------------------------------------------------------------------------
// Streaming decryption FFI.
//
// A stream clones the template (Arc) at creation, so the caller may destroy
// the original `tmpl` handle while the stream lives. Samples are submitted
// incrementally and plaintexts come back **in submission order**. Library is
// blocking; callers wrap `stream_next` in a thread for async semantics.
// ---------------------------------------------------------------------------

/// Create a streaming decryptor. `tmpl` is cloned into an Arc. `batch_size`
/// (>=1) bounds the adaptive batch. Returns a non-null handle or NULL on error.
#[no_mangle]
pub extern "C" fn stream_new(tmpl: *const c_void, batch_size: usize) -> *mut c_void {
    let res = catch_unwind(AssertUnwindSafe(|| {
        if tmpl.is_null() {
            return std::ptr::null_mut();
        }
        let t = unsafe { &*(tmpl as *const Template) };
        let s = StreamDecryptor::new(Arc::new(t.clone()), batch_size);
        Box::into_raw(Box::new(s)) as *mut c_void
    }));
    res.unwrap_or(std::ptr::null_mut())
}

/// Submit one encrypted sample. Blocks when the internal buffer is full
/// (backpressure). The handle must not be NULL.
#[no_mangle]
pub extern "C" fn stream_submit(s: *const c_void, sample: *const u8, len: usize) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if s.is_null() || (len > 0 && sample.is_null()) {
            return;
        }
        let st = unsafe { &*(s as *const StreamDecryptor) };
        let data = unsafe { slice::from_raw_parts(sample, len) }.to_vec();
        let _ = st.submit(data);
    }));
}

/// Blocking in-order receive. Returns the plaintext length written into `out`
/// (out must have capacity >= the sample length), or 0 when the stream is
/// closed and everything is consumed.
#[no_mangle]
pub extern "C" fn stream_next(s: *const c_void, out: *mut u8, cap: usize) -> usize {
    let res = catch_unwind(AssertUnwindSafe(|| {
        if s.is_null() || out.is_null() {
            return 0usize;
        }
        let st = unsafe { &*(s as *const StreamDecryptor) };
        match st.next() {
            Some(plain) => {
                let n = plain.len().min(cap);
                unsafe { std::ptr::copy_nonoverlapping(plain.as_ptr(), out, n) };
                n
            }
            None => 0usize,
        }
    }));
    res.unwrap_or(0)
}

/// Non-blocking probe. Returns 1 = plaintext written into `out` (`*out_len` =
/// length), 0 = no data pending yet, -1 = stream closed.
#[no_mangle]
pub extern "C" fn stream_try_next(
    s: *const c_void,
    out: *mut u8,
    cap: usize,
    out_len: *mut usize,
) -> i32 {
    let res = catch_unwind(AssertUnwindSafe(|| {
        if s.is_null() || out.is_null() || out_len.is_null() {
            return -1i32;
        }
        let st = unsafe { &*(s as *const StreamDecryptor) };
        match st.try_next() {
            crate::stream::StreamNext::Data(plain) => {
                let n = plain.len().min(cap);
                unsafe { std::ptr::copy_nonoverlapping(plain.as_ptr(), out, n) };
                unsafe { *out_len = n };
                1i32
            }
            crate::stream::StreamNext::Empty => 0i32,
            crate::stream::StreamNext::Closed => -1i32,
        }
    }));
    res.unwrap_or(-1)
}

/// Close the input side. Already-submitted samples still drain via
/// `stream_next` / `stream_try_next`.
#[no_mangle]
pub extern "C" fn stream_finish(s: *const c_void) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if s.is_null() {
            return;
        }
        let st = unsafe { &*(s as *const StreamDecryptor) };
        st.finish();
    }));
}

/// Destroy the stream handle (joins the coordinator thread). NULL is a no-op.
#[no_mangle]
pub extern "C" fn stream_destroy(s: *mut c_void) {
    if s.is_null() {
        return;
    }
    unsafe { drop(Box::from_raw(s as *mut StreamDecryptor)) };
}
