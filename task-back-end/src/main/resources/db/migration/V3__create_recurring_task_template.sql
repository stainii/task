CREATE TABLE recurring_task_template
(
    id                                    UUID PRIMARY KEY,
    name                                  VARCHAR(255) NOT NULL,
    min_number_of_days_between_executions INT          NOT NULL,
    max_number_of_days_between_executions INT          NOT NULL,
    creation_date                         DATE         NOT NULL,
    active_task                           BOOLEAN      NOT NULL,
    importance                            VARCHAR(50),
    context                               VARCHAR(255),
    description                           TEXT,
    version                               BIGINT       NOT NULL
);

CREATE TABLE execution
(
    id                UUID PRIMARY KEY,
    index             INTEGER NOT NULL,
    recurring_task_id UUID    NOT NULL,
    date              DATE    NOT NULL,

    CONSTRAINT fk_recurring_task
        FOREIGN KEY (recurring_task_id)
            REFERENCES recurring_task_template (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_execution_task_id ON execution (recurring_task_id);
