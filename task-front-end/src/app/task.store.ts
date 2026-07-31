import {patchState, signalStore, withHooks, withMethods, withState} from '@ngrx/signals';
import {Task} from './model';
import {effect, inject} from '@angular/core';
import {fetchEventSource} from '@microsoft/fetch-event-source';
import Keycloak from 'keycloak-js';
import {HttpClient} from '@angular/common/http';

const STORAGE_KEY_PREFIX = 'stijnhooft-task-';
const LAST_UPDATED_KEY = 'last-updated';
const TASKS_KEY = 'tasks';


type TaskStoreState = {
  tasks: Task[];
  loading: boolean;
  lastUpdated: Date | null;
  error: string | null;
};

const initialState: TaskStoreState = {
  tasks: [],
  loading: false,
  lastUpdated: null,
  error: null
};

export const TaskStore = signalStore(
  {providedIn: 'root'},
  withState(initialState),
  withMethods((store, http = inject(HttpClient)) => ({
    fetchTasks() {
      patchState(store, {loading: true});

      http.get<Task[]>('/api/tasks').subscribe({
        next: (tasks) => {
          patchState(store, {
            tasks,
            loading: false,
            lastUpdated: new Date()
          });
        },
        error: (err) => {
          patchState(store, {
            error: err.message,
            loading: false
          });
          console.error('Failed to fetch tasks', err);
        }
      });
    },

    updateLastUpdated(date: Date) {
      setLastUpdatedInLocalStorage(date);
      patchState(store, {lastUpdated: date});
    },

    initStreaming() {
      const keycloak = inject(Keycloak)
      const token = keycloak.token;
      const lastUpdated = store.lastUpdated();

      if (!token) {
        console.warn("No token available, skipping task patch streaming");
        return;
      }

      const url = `/api/task-patches?since=${lastUpdated?.toISOString()}`;
      return fetchEventSource(url, {
        headers: {
          'Authorization': `Bearer ${token}`,
          'Accept': 'text/event-stream'
        },
        async onopen(response) {
          console.debug("connected to task patch stream", response);
          patchState(store, {loading: false});
        },
        onmessage(event) {
          if (event.event === 'heartbeat') {
            console.trace("Received heartbeat for task patches stream");
            return;
          }

          console.info("received task patch", event);
          patchState(store, {lastUpdated: new Date(), tasks: [...store.tasks(), JSON.parse(event.data) as Task]});

        },
        onerror(err) {
          console.error('Error in task patch stream', err);
        }
      });

    }
  })),
  withHooks({
    onInit(store) {
      // TODO: first load local stage into store
      let lastUpdated = getLastUpdatedFromLocalStorage();

      if (lastUpdated == null || (new Date().getTime() - lastUpdated.getTime()) > 30 * 24 * 60 * 60 * 1000) {
        store.fetchTasks();
        store.updateLastUpdated(new Date());
      } else {
        store.updateLastUpdated(lastUpdated);
      }

      // init streaming after initial load to avoid receiving patches for tasks that are not yet loaded in the store
      store.initStreaming();

      // TODO: Sync changes back to localStorage automatically
      effect(() => {
        const tasks = store.tasks(); // This tracks the signal
        const lastUpdated = store.lastUpdated();

        localStorage.setItem(STORAGE_KEY_PREFIX + TASKS_KEY, JSON.stringify(tasks));
        if (lastUpdated) {
          localStorage.setItem(STORAGE_KEY_PREFIX + LAST_UPDATED_KEY, lastUpdated.toISOString());
        }
      });

    }
  })
);

// TODO move to separate service
function getLastUpdatedFromLocalStorage(): Date | null {
  const lastUpdatedString = localStorage.getItem(STORAGE_KEY_PREFIX + LAST_UPDATED_KEY);
  return lastUpdatedString == null ? null : new Date(lastUpdatedString);
}

function setLastUpdatedInLocalStorage(date: Date) {
  localStorage.setItem(STORAGE_KEY_PREFIX + LAST_UPDATED_KEY, date.toString());
}
