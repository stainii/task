/**
 * The thinnest possible promise wrapper over IndexedDB.
 *
 * Hand-rolled rather than pulling in `idb`, on the standing "prefer fewer moving parts": what the
 * store actually needs is three functions, and a dependency is a thing to keep current forever.
 */

export function request<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error ?? new Error('IndexedDB request failed.'));
  });
}

/**
 * Resolves when the transaction has **committed**, not when its last request succeeded.
 *
 * The difference is the whole point of the outbox: a patch that is queued but not committed is a
 * patch a browser kill loses, and a caller that resolves on `onsuccess` has no idea which it has.
 */
export function committed(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('IndexedDB transaction failed.'));
    tx.onabort = () => reject(tx.error ?? new Error('IndexedDB transaction aborted.'));
  });
}

export function open(
  name: string,
  version: number,
  upgrade: (db: IDBDatabase) => void,
): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(name, version);
    req.onupgradeneeded = () => upgrade(req.result);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () =>
      reject(req.error ?? new Error(`Could not open IndexedDB database ${name}.`));
    // Reported rather than waited out. Whoever is blocking has to let go, and only the *owner* of a
    // connection can do that — see `LocalStore.openDatabase`, which is where the yielding lives
    // because that is where the connection is cached.
    req.onblocked = () =>
      reject(new Error(`Opening ${name} is blocked by another tab holding an older version.`));
  });
}
