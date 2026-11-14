//! Streaming / incremental decryption (pure std, blocking semantics).
//!
//! Samples arrive incrementally (e.g. from a download stream): call
//! [`StreamDecryptor::submit`] as they come, then [`StreamDecryptor::next`]
//! receives plaintexts **in submission order**. A coordinator thread adaptively
//! batches submitted samples (up to `batch_size`, or after a short idle window)
//! and decrypts each batch on the process-wide parallel pool, so throughput
//! scales with the pool while latency stays low for trickles.
//!
//! The library stays pure std and blocking; the language bindings layer async
//! on top (Python `asyncio.to_thread`, Go goroutine+channel).

use crate::rounds::{Template, decrypt_par_into};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// Idle window after which a partial batch is flushed (bounds latency).
const IDLE_FLUSH: Duration = Duration::from_millis(2);

/// Result of a non-blocking [`StreamDecryptor::try_next`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StreamNext<T> {
    /// A plaintext is ready.
    Data(T),
    /// No plaintext pending yet (stream still open, more may come).
    Empty,
    /// The stream is closed (submitted samples all consumed).
    Closed,
}

/// Error from [`StreamDecryptor::submit`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StreamError {
    /// `finish()` was already called (input closed).
    Closed,
}

impl std::fmt::Display for StreamError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "stream is closed")
    }
}
impl std::error::Error for StreamError {}

/// Incremental parallel decryption with in-order results.
pub struct StreamDecryptor {
    tx_in: Mutex<Option<mpsc::SyncSender<Vec<u8>>>>,
    rx_out: mpsc::Receiver<Vec<u8>>,
    closed: AtomicBool,
    handle: Option<std::thread::JoinHandle<()>>,
}

impl StreamDecryptor {
    /// Create a stream over `tmpl`. `batch_size` (>=1) bounds the adaptive
    /// batch; samples submitted while the coordinator is full block
    /// (backpressure).
    pub fn new(tmpl: Arc<Template>, batch_size: usize) -> Self {
        let batch = batch_size.max(1);
        // bounded input channel: backpressure so a fast producer cannot
        // outrun the decryptor by more than `batch` samples
        let (tx_in, rx_in) = mpsc::sync_channel::<Vec<u8>>(batch * 2);
        let (tx_out, rx_out) = mpsc::channel::<Vec<u8>>();
        let handle = std::thread::spawn(move || coordinator(tmpl, rx_in, tx_out, batch));
        StreamDecryptor {
            tx_in: Mutex::new(Some(tx_in)),
            rx_out,
            closed: AtomicBool::new(false),
            handle: Some(handle),
        }
    }

    /// Submit one encrypted sample. Blocks when the internal buffer is full
    /// (backpressure); errors with [`StreamError::Closed`] after `finish()`.
    pub fn submit(&self, sample: Vec<u8>) -> Result<(), StreamError> {
        match self.tx_in.lock().unwrap().as_ref() {
            Some(tx) => tx.send(sample).map_err(|_| StreamError::Closed),
            None => Err(StreamError::Closed),
        }
    }

    /// Non-blocking submit; errors when the internal buffer is full.
    pub fn try_submit(&self, sample: Vec<u8>) -> Result<(), StreamError> {
        match self.tx_in.lock().unwrap().as_ref() {
            Some(tx) => tx.try_send(sample).map_err(|_| StreamError::Closed),
            None => Err(StreamError::Closed),
        }
    }

    /// Block for the next plaintext (in submission order). Returns `None`
    /// once the stream is closed and everything is consumed.
    pub fn next(&self) -> Option<Vec<u8>> {
        self.rx_out.recv().ok()
    }

    /// Non-blocking probe.
    pub fn try_next(&self) -> StreamNext<Vec<u8>> {
        match self.rx_out.try_recv() {
            Ok(v) => StreamNext::Data(v),
            Err(mpsc::TryRecvError::Empty) => StreamNext::Empty,
            Err(mpsc::TryRecvError::Disconnected) => StreamNext::Closed,
        }
    }

    /// Close the input side. Already-submitted samples are still decrypted
    /// and consumed via `next()`/`try_next()`.
    pub fn finish(&self) {
        // drop the sender: coordinator drains and closes
        *self.tx_in.lock().unwrap() = None;
        self.closed.store(true, Ordering::Release);
    }

    /// Whether `finish()` was called.
    pub fn is_finished(&self) -> bool {
        self.closed.load(Ordering::Acquire)
    }
}

impl Drop for StreamDecryptor {
    fn drop(&mut self) {
        // close input first so the coordinator drains and exits
        *self.tx_in.get_mut().unwrap() = None;
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

/// Coordinator thread: receive samples, adaptively batch, decrypt on the
/// global pool, forward results in order.
fn coordinator(
    tmpl: Arc<Template>,
    rx_in: mpsc::Receiver<Vec<u8>>,
    tx_out: mpsc::Sender<Vec<u8>>,
    batch: usize,
) {
    let mut buffer: Vec<Vec<u8>> = Vec::with_capacity(batch);
    let mut input_open = true;
    while input_open || !buffer.is_empty() {
        // fill up to `batch` (or flush after a short idle window)
        if buffer.is_empty() {
            match rx_in.recv_timeout(IDLE_FLUSH) {
                Ok(s) => buffer.push(s),
                Err(mpsc::RecvTimeoutError::Timeout) => { /* idle: nothing to do */ }
                Err(mpsc::RecvTimeoutError::Disconnected) => input_open = false,
            }
        }
        while buffer.len() < batch && input_open {
            match rx_in.recv_timeout(IDLE_FLUSH) {
                Ok(s) => buffer.push(s),
                Err(mpsc::RecvTimeoutError::Timeout) => break, // flush what we have
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    input_open = false;
                }
            }
        }
        if buffer.is_empty() {
            continue;
        }
        // decrypt the batch in order
        let n = buffer.len();
        let refs: Vec<&[u8]> = buffer.iter().map(|s| s.as_slice()).collect();
        let mut offs = Vec::with_capacity(n);
        let mut total = 0usize;
        for s in buffer.iter() {
            offs.push(total);
            total += s.len();
        }
        let mut out = vec![0u8; total];
        decrypt_par_into(&tmpl, &refs, &offs, &mut out);
        for (i, s) in buffer.iter().enumerate() {
            let l = s.len();
            if tx_out.send(out[offs[i]..offs[i] + l].to_vec()).is_err() {
                return; // consumer gone
            }
        }
        buffer.clear();
    }
}