import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { IsoDate } from '../domain/dates';
import { TaskTemplate } from '../domain/template';

/** What running a manual template takes: the variables, and **one** anchor date (ADR-0013). */
export interface TemplateEntry {
  readonly variables: Readonly<Record<string, string>>;
  readonly anchorDate: IsoDate | null;
}

/**
 * `/api/task-templates` — **plain CRUD, and deliberately not the patch outbox.**
 *
 * Patching works for a task precisely because a task is inert; a template is a rule that keeps
 * running in your absence, so two devices editing one offline would produce a firing schedule
 * neither of them chose (`CONTEXT.md`). Every method here therefore needs the server, and the
 * authoring screen says so rather than queueing (ADR-0004: *visibly unavailable offline, not
 * silently failing*).
 *
 * The reads are the exception that proves it: the list is fetched and **held**, because the ✓, the
 * omnibox rows and ADR-0011's offline mint all read templates with the radio off.
 */
@Injectable({ providedIn: 'root' })
export class TemplateApi {
  private static readonly URL = '/api/task-templates';

  private readonly http = inject(HttpClient);

  list(): Promise<TaskTemplate[]> {
    return firstValueFrom(this.http.get<TaskTemplate[]>(TemplateApi.URL));
  }

  /** The id is the client's, as it is for a task: minted here so a draft has one before it is sent. */
  create(template: TaskTemplate): Promise<TaskTemplate> {
    return firstValueFrom(this.http.post<TaskTemplate>(TemplateApi.URL, template));
  }

  update(template: TaskTemplate): Promise<TaskTemplate> {
    return firstValueFrom(
      this.http.put<TaskTemplate>(`${TemplateApi.URL}/${template.id}`, template),
    );
  }

  /**
   * **Stop firing**, and its inverse. Two endpoints rather than a field on the `PUT`, because both
   * writes move `activeSince` and a `PUT` that could flip `active` would be a second, silent path
   * to reactivation — one that leaves a calendar template free to catch up on the months it spent
   * switched off.
   */
  deactivate(id: string): Promise<TaskTemplate> {
    return firstValueFrom(
      this.http.post<TaskTemplate>(`${TemplateApi.URL}/${id}/deactivation`, {}),
    );
  }

  reactivate(id: string): Promise<TaskTemplate> {
    return firstValueFrom(
      this.http.post<TaskTemplate>(`${TemplateApi.URL}/${id}/reactivation`, {}),
    );
  }

  /** `409` once the template has produced a single task. Deleting is for the one made by mistake. */
  remove(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`${TemplateApi.URL}/${id}`));
  }

  /**
   * **Running a template creates its tasks immediately** (ADR-0013), server-side.
   *
   * Not the client-minted path: that one exists because *"I already did this"* must work offline,
   * where this one is a deliberate act at a keyboard with the anchor date being typed. The client's
   * renderer still runs — as the **preview**, which is the check that replaces an
   * edit-before-create step.
   */
  run(id: string, entry: TemplateEntry): Promise<void> {
    return firstValueFrom(this.http.post<void>(`${TemplateApi.URL}/${id}/tasks`, entry));
  }
}
