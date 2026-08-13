package be.stijnhooft.task.backend.migration.portal;

import com.mongodb.DBRef;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.jspecify.annotations.Nullable;

import be.stijnhooft.task.backend.task.Importance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Reads portal's `todo` Mongo database from a **restored dump**, never from live portal
/// ([ADR-0005](../../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md)).
///
/// The four collections it reads are `task`, `taskPatch`, `taskTemplate` and `subscription`. The
/// last one is not migrated — [#12](https://github.com/stainii/task/issues/12) killed the whole
/// subscription block — but it is read for one field: `origin` holds the four deployment names that
/// [be.stijnhooft.task.backend.migration.map.FlowId] matches against, and nothing else in the corpus
/// names them.
///
/// `Task.history` is a `@DBRef` list into `taskPatch` and is **deliberately not followed**. Patches
/// are grouped by their own `taskId` instead, which is what recovers the 32 patches portal's array
/// had lost. The two agree everywhere else: no patch in the archive appears in the history of a task
/// other than the one its `taskId` names.
@Slf4j
public class TodoReader implements AutoCloseable {

    private final MongoClient client;
    private final String databaseName;

    public TodoReader(String connectionString, String databaseName) {
        this.client = MongoClients.create(connectionString);
        this.databaseName = databaseName;
    }

    /// The deployment names, from `subscription.origin`. ADR-0005 first told the importer to derive
    /// this set from the `flowId`s because nothing else named the deployments; this does, and the
    /// derivation survives as the cross-check the importer aborts on.
    public List<String> deploymentNames() {
        var names = new ArrayList<String>();
        collection("subscription").find().forEach(document -> {
            var origin = document.getString("origin");
            if (origin != null && !names.contains(origin)) {
                names.add(origin);
            }
        });
        return List.copyOf(names);
    }

    /// The importance each deployment's generated tasks actually had, read from the subscription
    /// that generated them.
    ///
    /// [#12](https://github.com/stainii/task/issues/12) killed subscriptions, and rightly — a user
    /// authoring SpEL over a bus is not coming back. But one of their `mappingToTask` expressions is
    /// a plain quoted literal and it is the only record of a real product decision: **Setlist's
    /// tasks were `NOT_SO_IMPORTANT` and the other three were `IMPORTANT`**, for years. Portal's
    /// `recurring_task` table has no importance column, so without this every migrated setlist
    /// template would start producing more urgent tasks than it ever did.
    ///
    /// Only a **simple quoted literal** is honoured. Anything else — a real expression, a reference
    /// to event data — is not evaluated: there is no SpEL here and no event to evaluate it against,
    /// and a half-parsed expression is how a guess comes to look like a fact.
    public Map<String, Importance> importanceByDeployment() {
        var byDeployment = new LinkedHashMap<String, Importance>();
        collection("subscription").find().forEach(document -> {
            var origin = document.getString("origin");
            var mapping = document.get("mappingToTask", Document.class);
            if (origin == null || mapping == null) {
                return;
            }
            literal(mapping.getString("mappingOfImportance"))
                    .flatMap(TodoReader::importance)
                    .ifPresent(importance -> byDeployment.put(origin, importance));
        });
        return Map.copyOf(byDeployment);
    }

    /// `'IMPORTANT'` yields `IMPORTANT`; `data['urgent'] ? … : …` yields nothing.
    private static Optional<String> literal(@Nullable String expression) {
        if (expression == null) {
            return Optional.empty();
        }
        var trimmed = expression.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '\'' || !trimmed.endsWith("'")) {
            return Optional.empty();
        }
        var inner = trimmed.substring(1, trimmed.length() - 1);
        return inner.indexOf('\'') >= 0 ? Optional.empty() : Optional.of(inner);
    }

    private static Optional<Importance> importance(String value) {
        try {
            return Optional.of(Importance.valueOf(value));
        } catch (IllegalArgumentException unknown) {
            log.warn("Subscription names an importance this application does not have: {}", value);
            return Optional.empty();
        }
    }

    public List<PortalArchive.PortalTask> tasks() {
        var tasks = new ArrayList<PortalArchive.PortalTask>();
        collection("task").find().forEach(document -> tasks.add(new PortalArchive.PortalTask(
                id(document),
                document.getString("flowId"),
                requireString(document, "name"),
                document.getString("context"),
                document.getString("status"),
                document.getString("importance"),
                instant(document.get("creationDateTime")),
                instant(document.get("startDateTime")),
                instant(document.get("dueDateTime")),
                document.getString("description"),
                historyOrder(document))));
        return tasks;
    }

    /// The `history` array's order — insertion order, and so the order the patches *arrived* in.
    ///
    /// Read for the stored-versus-folded diff only. Portal's repair recursion re-`add`s patches, so
    /// the same id can appear twice; duplicates are kept rather than collapsed, because a duplicate
    /// is itself one of the causes the diff attributes a difference to.
    private static List<String> historyOrder(Document document) {
        var raw = document.get("history");
        if (!(raw instanceof List<?> references)) {
            return List.of();
        }
        var ids = new ArrayList<String>(references.size());
        for (var reference : references) {
            switch (reference) {
                case DBRef ref -> ids.add(String.valueOf(ref.getId()));
                // A plain id, should portal ever have written one. Never occurs in the archive:
                // all 11,855 tasks carry DBRefs.
                case null -> { }
                default -> ids.add(String.valueOf(reference));
            }
        }
        return List.copyOf(ids);
    }

    public List<PortalArchive.PortalPatch> patches() {
        var patches = new ArrayList<PortalArchive.PortalPatch>();
        collection("taskPatch").find().forEach(document -> {
            var dateTime = instant(document.get("dateTime"));
            if (dateTime == null) {
                // Never occurs in the archive. It would be unfoldable - dateTime is what orders the
                // fold - so it fails here rather than sorting arbitrarily.
                throw new IllegalStateException("Patch " + id(document) + " has no dateTime.");
            }
            patches.add(new PortalArchive.PortalPatch(
                    id(document),
                    requireString(document, "taskId"),
                    dateTime,
                    changes(document)));
        });
        return patches;
    }

    public List<PortalArchive.PortalTaskTemplate> taskTemplates() {
        var templates = new ArrayList<PortalArchive.PortalTaskTemplate>();
        collection("taskTemplate").find().forEach(document -> {
            var definitions = new ArrayList<PortalArchive.PortalTaskDefinition>();
            var embedded = document.getList("taskDefinitions", Document.class);
            if (embedded != null) {
                embedded.forEach(definition -> definitions.add(new PortalArchive.PortalTaskDefinition(
                        requireString(definition, "name"),
                        definition.getString("description"),
                        definition.getString("context"),
                        definition.getString("importance"),
                        definition.getInteger("startDateDeviationDays"),
                        definition.getInteger("dueDateDeviationDays"))));
            }
            templates.add(new PortalArchive.PortalTaskTemplate(
                    id(document), requireString(document, "name"), List.copyOf(definitions)));
        });
        return templates;
    }

    /// `changes` is a map of string to string, except that portal stored nulls in it. `getString`
    /// on a null value returns null either way, so absent and present-but-null would be
    /// indistinguishable - hence reading the raw map and keeping the keys.
    private static Map<String, String> changes(Document document) {
        var raw = document.get("changes", Document.class);
        var changes = new LinkedHashMap<String, String>();
        if (raw != null) {
            raw.forEach((key, value) -> changes.put(key, value == null ? null : String.valueOf(value)));
        }
        return changes;
    }

    /// `_id` is a string for all but 115 patches, which carry a Mongo `ObjectId`. Both are rendered
    /// as their string form and minted into a UUID downstream by
    /// [be.stijnhooft.task.backend.migration.map.PortalIds].
    private static String id(Document document) {
        var raw = document.get("_id");
        if (raw == null) {
            throw new IllegalStateException("A portal document has no _id: " + document.toJson());
        }
        return raw instanceof String string ? string : raw.toString();
    }

    private static String requireString(Document document, String field) {
        var value = document.getString(field);
        if (value == null) {
            throw new IllegalStateException(
                    "Portal document " + id(document) + " has no " + field + ", which cannot be mapped.");
        }
        return value;
    }

    /// Written as an early return rather than a `case null` arm: NullAway cannot see that a
    /// `default` reached after `case null` has a non-null subject, and a suppression would be a
    /// worse answer than the shape that does not need one.
    private static @Nullable Instant instant(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case Date date -> date.toInstant();
            case Instant instant -> instant;
            default -> Instant.parse(value.toString());
        };
    }

    private com.mongodb.client.MongoCollection<Document> collection(String name) {
        return client.getDatabase(databaseName).getCollection(name);
    }

    @Override
    public void close() {
        client.close();
    }
}
