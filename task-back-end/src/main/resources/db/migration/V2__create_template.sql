CREATE TABLE task_template
(
    id      UUID PRIMARY KEY,
    name    TEXT   NOT NULL,
    version BIGINT NOT NULL
);

CREATE TABLE task_template_variable_name
(
    task_template_id UUID    NOT NULL,
    index            INTEGER not null,
    variable_name    TEXT    NOT NULL,

    CONSTRAINT fk_task_template_variable_name
        FOREIGN KEY (task_template_id)
            REFERENCES task_template (id)
            ON DELETE CASCADE
);

CREATE TABLE task_definition
(
    id                        UUID PRIMARY KEY,
    task_template_id          UUID    NOT NULL,
    index                     INTEGER not null,

    name                      TEXT,
    start_date_deviation_days INTEGER,
    start_date_deviation_base TEXT,
    due_date_deviation_days   INTEGER,
    due_date_deviation_base   TEXT,
    context                   TEXT    NOT NULL,
    importance                TEXT,
    description               TEXT,

    CONSTRAINT fk_task_definition_template
        FOREIGN KEY (task_template_id)
            REFERENCES task_template (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_task_definition_template
    ON task_definition (task_template_id);

CREATE INDEX idx_task_template_variable_name_template
    ON task_template_variable_name (task_template_id);
