import {ChangeDetectionStrategy, Component, inject} from '@angular/core';
import {TaskStore} from '../task.store';
import {JsonPipe} from '@angular/common';

@Component({
  selector: 'app-task-list',
  imports: [
    JsonPipe
  ],
  templateUrl: './task-list.html',
  styleUrl: './task-list.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TaskList {

  readonly store = inject(TaskStore);


}
