export type Task = {
  id: string;
  name: string;
  creationDateTime: Date;
  startDate: Date;
  dueDate: Date;
  context: string;
  importance: Importance;
  description: string;
  status: TaskStatus;
}

export enum Importance {
  I_DO_NOT_REALLY_CARE = "I_DO_NOT_REALLY_CARE",
  NOT_SO_IMPORTANT = "NOT_SO_IMPORTANT",
  IMPORTANT = "IMPORTANT",
  VERY_IMPORTANT = "VERY_IMPORTANT"
}

export enum TaskStatus {
  OPEN = "OPEN",
  COMPLETED = "COMPLETED",
  CANCELLED = "CANCELLED"
}
