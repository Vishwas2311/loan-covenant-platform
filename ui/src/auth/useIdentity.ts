import { useSyncExternalStore } from 'react';
import { getIdentity, subscribe, type Identity } from './identity';

let cached: Identity = getIdentity();
let cachedKey = JSON.stringify(cached);

function snapshot(): Identity {
  const next = getIdentity();
  const key = JSON.stringify(next);
  if (key !== cachedKey) {
    cached = next;
    cachedKey = key;
  }
  return cached;
}

/** The current identity; re-renders when the user switches identity. */
export function useIdentity(): Identity {
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}
