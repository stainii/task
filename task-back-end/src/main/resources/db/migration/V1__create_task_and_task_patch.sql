CREATE TABLE task
(
    id                 UUID PRIMARY KEY,
    name               VARCHAR(255) NOT NULL,
    creation_date_time TIMESTAMP    NOT NULL,
    start_date         DATE         NOT NULL,
    due_date           DATE         NULL,
    context            VARCHAR(255) NOT NULL,
    importance         VARCHAR(20)  NULL,
    description        TEXT         NULL,
    status             VARCHAR(10)  NOT NULL,
    version            INT          NOT NULL
);

CREATE INDEX idx_task_status ON task (status);

CREATE TABLE task_patch
(
    id          UUID PRIMARY KEY,
    task_id     UUID      NOT NULL,
    date_time   TIMESTAMP NOT NULL,
    changes     JSONB     NOT NULL,
    order_index INT       NOT NULL,
    version     INT       NOT NULL,

    CONSTRAINT fk_task_patch_task FOREIGN KEY (task_id)
        REFERENCES task (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_task_patch_task_date_time
        UNIQUE (task_id, date_time)
);

CREATE INDEX idx_task_patch_task_id ON task_patch (task_id);

