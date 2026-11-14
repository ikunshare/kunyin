//! Persistent worker pool for parallel sample decryption (pure std).
//!
//! `decrypt_par` used to spawn `available_parallelism` OS threads on
//! every call (`std::thread::scope`), costing ~0.7-1 ms per call in thread
//! creation alone (measured on WSL). This module replaces that with a pool of
//! long-lived worker threads that are reused across calls (epoch-based job
//! dispatch), eliminating the per-call spawn cost entirely.
//!
//! Design:
//!   * Workers sleep on a condvar until a new job epoch is published.
//!   * Each worker pulls work from a shared atomic counter, so a job is
//!     processed by all workers in parallel; each index is handled by exactly
//!     one worker.
//!   * The caller blocks until `pending == 0` (a completion flag + condvar),
//!     which also guarantees the borrowed input/output pointers stay valid
//!     for the whole call — this is what makes the raw-pointer job sound.
//!
//! Concurrency: the pool is process-global and dispatches one job at a time,
//! so concurrent `par_decrypt_into` callers serialize on the pool (each call
//! is internally parallel). This matches the hot-path usage (one caller
//! batches thousands of samples per call).

use crate::rounds::Template;
use std::slice;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};

struct Job {
    tmpl: *const Template,
    ptrs: *const *const u8,
    lens: *const usize,
    offs: *const usize,
    n: usize,      // number of samples
    pairs: usize,  // number of 2-lane work items (ceil(n/2))
    next: AtomicUsize,
    pending: AtomicUsize,
    finished: AtomicBool,
    out: *mut u8,
}
// SAFETY: `run()` blocks until `finished`, so `tmpl`/`ptrs`/`lens`/`offs`/`out`
// remain valid for the whole call. Each index i is processed by exactly one
// worker and writes a disjoint output region, so the shared `Job` is sound.
unsafe impl Send for Job {}
unsafe impl Sync for Job {}

struct Shared {
    job: Mutex<Option<(Arc<Job>, u64)>>,
    cv_job: Condvar,
    shutdown: AtomicBool,
    epoch: AtomicUsize,
    done_mutex: Mutex<()>,
    cv_done: Condvar,
    // serializes concurrent run() calls: the pool has one job slot, so a
    // second concurrent call would overwrite the first's job and deadlock it.
    // Concurrent callers queue here (each call is internally parallel).
    run_lock: Mutex<()>,
}

struct Pool {
    shared: Arc<Shared>,
    threads: Vec<std::thread::JoinHandle<()>>,
}

impl Drop for Pool {
    /// Signal shutdown, wake all workers, and join them. Only runs when a
    /// Pool is dropped directly (the process-global `POOL` OnceLock never
    /// drops, so library users get process-lifetime workers — the intended
    /// hot-path behaviour).
    fn drop(&mut self) {
        self.shared.shutdown.store(true, Ordering::Release);
        self.shared.cv_job.notify_all();
        for t in self.threads.drain(..) {
            let _ = t.join();
        }
    }
}

impl Pool {
    fn new(workers: usize) -> Pool {
        let shared = Arc::new(Shared {
            job: Mutex::new(None),
            cv_job: Condvar::new(),
            shutdown: AtomicBool::new(false),
            epoch: AtomicUsize::new(0),
            done_mutex: Mutex::new(()),
            cv_done: Condvar::new(),
            run_lock: Mutex::new(()),
        });
        let mut threads = Vec::with_capacity(workers);
        for _ in 0..workers {
            let s = shared.clone();
            threads.push(std::thread::spawn(move || worker_loop(s)));
        }
        Pool { shared, threads }
    }

    fn run(
        &self,
        tmpl: &Template,
        ptrs: &[*const u8],
        lens: &[usize],
        offs: &[usize],
        out: &mut [u8],
    ) {
        let n = lens.len();
        if n == 0 {
            return;
        }
        let pairs = (n + 1) / 2;
        // serialize with other concurrent run() calls (single job slot)
        let _run_guard = self.shared.run_lock.lock().unwrap();
        let job = Arc::new(Job {
            tmpl,
            ptrs: ptrs.as_ptr(),
            lens: lens.as_ptr(),
            offs: offs.as_ptr(),
            n,
            pairs,
            next: AtomicUsize::new(0),
            pending: AtomicUsize::new(pairs),
            finished: AtomicBool::new(false),
            out: out.as_mut_ptr(),
        });
        let epoch = self.shared.epoch.fetch_add(1, Ordering::Relaxed) as u64 + 1;
        {
            let mut g = self.shared.job.lock().unwrap();
            *g = Some((job.clone(), epoch));
        }
        self.shared.cv_job.notify_all();
        let mut g = self.shared.done_mutex.lock().unwrap();
        while !job.finished.load(Ordering::Acquire) {
            g = self.shared.cv_done.wait(g).unwrap();
        }
    }
}

fn worker_loop(shared: Arc<Shared>) {
    let mut my_epoch = 0u64;
    loop {
        let job = {
            let mut g = shared.job.lock().unwrap();
            loop {
                if let Some((j, e)) = g.as_ref() {
                    if *e != my_epoch {
                        break j.clone();
                    }
                }
                if shared.shutdown.load(Ordering::Acquire) {
                    return;
                }
                g = shared.cv_job.wait(g).unwrap();
            }
        };
        my_epoch += 1;
        loop {
            let p = job.next.fetch_add(1, Ordering::Relaxed);
            if p >= job.pairs {
                break;
            }
            // SAFETY: run() blocks until finished; ptrs/lens/offs/out/tmpl
            // live for the whole call; each pair (2i, 2i+1) is owned by
            // exactly one worker and writes disjoint output regions.
            let i1 = p * 2;
            let (ptr1, len1, off1) = unsafe {
                (*job.ptrs.add(i1), *job.lens.add(i1), *job.offs.add(i1))
            };
            let sample1 = unsafe { slice::from_raw_parts(ptr1, len1) };
            let out1 = unsafe { slice::from_raw_parts_mut(job.out.add(off1), len1) };
            let tmpl = unsafe { &*job.tmpl };
            let i2 = i1 + 1;
            if i2 < job.n {
                let (ptr2, len2, off2) = unsafe {
                    (*job.ptrs.add(i2), *job.lens.add(i2), *job.offs.add(i2))
                };
                let sample2 = unsafe { slice::from_raw_parts(ptr2, len2) };
                let out2 = unsafe { slice::from_raw_parts_mut(job.out.add(off2), len2) };
                crate::rounds::decrypt_region_pair_into(tmpl, sample1, sample2, out1, out2);
            } else {
                crate::rounds::decrypt_region_into(tmpl, sample1, out1);
            }
            if job.pending.fetch_sub(1, Ordering::AcqRel) == 1 {
                job.finished.store(true, Ordering::Release);
                shared.cv_done.notify_all();
            }
        }
    }
}

static POOL: OnceLock<Pool> = OnceLock::new();

fn pool() -> &'static Pool {
    POOL.get_or_init(|| {
        let workers = std::env::var("TEMARI_WORKERS")
            .ok()
            .and_then(|v| v.parse::<usize>().ok())
            .filter(|&w| w > 0)
            .unwrap_or_else(|| {
                std::thread::available_parallelism().map(|p| p.get()).unwrap_or(4)
            });
        Pool::new(workers)
    })
}

/// Decrypt `samples` in parallel, writing each plaintext into `out` at the
/// offset given by `offs[i]` (each region is exactly `len == samples[i].len()`).
/// Caller must ensure `out.len() >= offs[n-1] + lens[n-1]`.
pub(crate) fn par_decrypt_into(
    tmpl: &Template,
    samples: &[&[u8]],
    offs: &[usize],
    out: &mut [u8],
) {
    let n = samples.len();
    if n == 0 {
        return;
    }
    if n == 1 {
        crate::rounds::decrypt_region_into(tmpl, samples[0], &mut out[offs[0]..offs[0] + samples[0].len()]);
        return;
    }
    // build raw pointer + lens arrays (the job can't hold `&[u8]` refs)
    let ptrs: Vec<*const u8> = samples.iter().map(|s| s.as_ptr()).collect();
    let lens: Vec<usize> = samples.iter().map(|s| s.len()).collect();
    pool().run(tmpl, &ptrs, &lens, offs, out);
}