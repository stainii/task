-- #51 / ADR-0012: the whole persistent footprint of the notification feature - one row per device.
--
-- There is deliberately no notification table. A notification is a projection of the tasks due that
-- day, computed at 07:30 and not remembered: portal needed 8,201 rows with read/published/
-- cancelled_at/scheduled_at because a notification was in flight over RabbitMQ, and 336 of them were
-- ever read. Inside one deployable there is nothing in flight and nothing to remember.

CREATE TABLE push_subscription
(
    id            UUID PRIMARY KEY,

    -- Where the browser wants its messages posted. The device's own identity, and therefore the
    -- key registration is idempotent on: the client re-registers on every app open, so a plain
    -- insert would give one phone a row a day and one notification per row.
    endpoint      TEXT        NOT NULL,

    -- The device's P-256 public key and the subscription's auth secret, base64url, exactly as the
    -- PushManager handed them over. Together they are what makes a message readable on that device
    -- and nowhere else; neither is a secret of ours, and both are worthless without the endpoint.
    p256dh        TEXT        NOT NULL,
    auth          TEXT        NOT NULL,

    -- Not read by anything. Kept because a device list with no dates cannot answer "is this the
    -- phone I replaced in March?", and nothing else in the system records a device at all.
    registered_on TIMESTAMPTZ NOT NULL,

    version       BIGINT      NOT NULL,

    CONSTRAINT uq_push_subscription_endpoint UNIQUE (endpoint)
);
