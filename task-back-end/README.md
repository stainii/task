# Task (back-end)

[![CI](https://github.com/stainii/task/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/stainii/task/actions/workflows/ci.yml)

## TODO list
- [ ] Goals

A todo app? Yes, but a smart one.

## Inspired by "The 7 Habits of Highly Effective People"
This todo app is built around the principles of the famous 7 habits book, dividing tasks in 4 categories:

* Urgent, important             => Fires to extinguish
* Not urgent, important         => The tasks that help you the most
* Urgent, not important         => Tasks to be done sometime, but to be avoided in the future
* Not urgent, not important     => Tasks to never look at again, unless I'm really bored

## Task templates
Some tasks always lead to other tasks.
For example, when organizing an event, multiple tasks have to be created: send out invitations, agree on a date, get everything ready, follow-up afterwards how everyone experienced the event, ...

With task templates, you can create all these tasks in one go.

## Recurring tasks templates
You can create recurring tasks that
* should pop up on a certain date
* or should reoccur x days after the completion of the previous task instance


## Offline support, on multiple devices
This application should support
* **offline use**
* use on **multiple devices** (which means: the list of tasks should be up to date on all devices on all times)

These are 2 conflicting requirements.

### Conflicting changes
If I'm offline and I edit a task, this edit cannot be sent to the server until I get online.
It can take hours or days before the change gets pushed.

What if I make another change to the same task on another device, that gets synced before my first change gets synced?

To mediate this problem, the applications should **not send complete tasks** to the server, but **task patches**.

### What are task patches?
Task patches contain **a datetime** and a **description of the changes** that need to be made.
These task patches get **applied to the patch**, **in order** of their datetime.

An example:

**Task:**
``` json
{
    "id": "abc",
    "name": "my fancy task",
    "context": "Personal",
    "creationDateTime": "2020-03-01 12:00:00",
    "history": [
        "id": "aaa",
        "taskId": "abc",
        "dateTime": "2020-03-01 12:00:00",
        "changes": {
            "name": "my fancy task",
            "context": "Personal"
        }
    ]
}
```

**Task patch A:**
``` json
{
    "id": "def",
    "taskId": "abc",
    "dateTime": "2020-03-10 07:00:00",
    "changes": {
        "name": "my superfancy task",
        "description": "Isn't this a fancy task?"
    }
}
```

**Task patch B:**
``` json
{
    "id": "def",
    "taskId": "abc",
    "dateTime": "2020-03-11 07:00:00",
    "changes": {
        "name": "my great task"
    }
}
```

**Resulting task:**
``` json
{
    "id": "abc",
    "name": "my great task",
    "context": "Personal",
    "description": "Isn't this a fancy task?"
    "creationDateTime": "2020-03-01 12:00:00",
    "history": [
        {
            "id": "aaa",
            "taskId": "abc",
            "dateTime": "2020-03-01 12:00:00",
            "changes": {
                "name": "my fancy task",
                "context": "Personal"
            }
        }, {
            "id": "def",
            "taskId": "abc",
            "dateTime": "2020-03-11 07:00:00",
            "changes": {
                "name": "my great task"
            }
        }, {
           "id": "def",
           "taskId": "abc",
           "dateTime": "2020-03-11 07:00:00",
           "changes": {
               "name": "my great task"
           }
       }
    ]
}
```

It doesn't matter in task patch A gets sent to the server before task patch B. Even if A arrives later than B, they will be (re)applied in order of their date.

## Local Development

### Toolchain
The versions this repo is built and tested against are pinned in the repo, not left to whatever is on your machine:

| | Pinned in | Version |
|---|---|---|
| JDK | `.sdkmanrc` (repo root) | Temurin 26 |
| Node | `task-front-end/.nvmrc` | 26 |
| Maven | `.mvn/wrapper/maven-wrapper.properties` | 3.9.16 (via `./mvnw`) |
| Postgres | `compose.yaml` + `TestcontainersConfiguration` | 18.4 |
| Keycloak | `compose.yaml` + `AbstractIntegrationTestCases` | 26.7.0 |

Run `sdk env` in the repo root and `nvm use` in `task-front-end/` to pick these up.

One thing that will waste your time otherwise:

- **`~/.mavenrc` beats `sdk env`.** If you have a `~/.mavenrc` setting `JAVA_HOME` (to sdkman's `current` symlink, for instance), `./mvnw` uses *that* JDK regardless of the shell's `JAVA_HOME`, and the build fails with `release version 26 not supported`. Either make 26 your sdkman default (`sdk default java 26.0.2-tem`) or run with `MAVEN_SKIP_RC=1`.

### Faster local test runs

The integration tests start Postgres and Keycloak through Testcontainers, and both containers ask
to be reused. **Reuse is opt-in per machine and does nothing until you enable it:**

```
echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties
```

With it on, the containers survive between runs and `./mvnw verify` skips ~20 seconds of container
startup; with it off, `withReuse(true)` is a no-op — which is also the case on CI, where a fresh
runner has nothing to reuse ([#21](https://github.com/stainii/task/issues/21)).

**The data does not survive with them.** `TestcontainersConfiguration#emptyOnce` drops and recreates
the schema once per run, before the first test touches Postgres, so a reused container behaves like
a fresh one and Flyway rebuilds from V1. Without it the suite's own leftovers accumulate at exactly
+34 open tasks per run and the eighth consecutive run fails with
`DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144` — `GET /api/tasks`
returns every open task with its full history, and it eventually outgrows `WebTestClient`'s default
buffer. Nothing to remember and nothing to run by hand; it is here so that **a local run and a CI
run mean the same thing**. Full story in `docs/quality-bar.md` §5.

One trap when running a single class: **`./mvnw surefire:test -Dtest=Foo` does not compile
anything** and will happily run a stale `Foo` from `target/`. Use `./mvnw test -Dtest=Foo`. Found
while canarying #44's boundary test: deliberately broken, it still "passed".

This is also why the Keycloak container in `AbstractIntegrationTestCases` is started and never
stopped: stopping it would defeat reuse on the next run, and Testcontainers' Ryuk removes it when
reuse is off. It is *not* the cause of the 30-second exit penalty that used to end every build —
that was Tomcat's graceful shutdown waiting on an SSE stream that never completes, fixed in
`SseEmitters` ([#44](https://github.com/stainii/task/issues/44)).

### Time and the clock

The application's zone is `task.time-zone` in `application.yml` (`Europe/Brussels`), and the only
way to read the current date in `src/main` is the `Clock` bean in `config/TimeConfig` —
`JavaTimeDefaultTimeZone` is an ERROR, so `LocalDate.now()` does not compile. Entities receive the
time from their caller; they never read it. In tests, use `TestClock` and move it rather than
waiting for the calendar.

### Static analysis

`./mvnw verify` runs **Error Prone + NullAway** over `src/main`, and it fails the build. Two things
to know before you touch its configuration in `pom.xml`:

- **The versions are pinned as a pair** (Error Prone 2.47.0, NullAway 0.12.7). 2.50.0 breaks
  NullAway; 2.41.0 does not run on JDK 26.
- **The `-Xplugin` argument must stay on one line.** Split it and javac cannot find the plugin, so
  Error Prone silently does not run and the build still goes green.

Existing violations are parked behind suppressions that each say why:
`grep -rn "Parked by #10" src` lists them. Delete one as part of the rewrite that fixes it.

Full rules: `docs/quality-bar.md`. CI proves this gate is live rather than trusting it — it compiles
a deliberate violation and requires the build to fail. See `docs/ci.md` §3.

### Mutation testing

There isn't any, on purpose — [#32](https://github.com/stainii/task/issues/32) removed pitest. It cost ~46 minutes on every `verify`, over half its surviving mutants were in MapStruct-generated `*MapperImpl` classes and `@Bean` methods that no test can kill, and the accuracy fix (the Arcmutate Spring plugin) is a paid subscription needing a licence file in this public repo. It remains a legitimate ad-hoc tool — add the plugin, run it, take it out again — but nothing in the build depends on it. **Please read #32 before reinstating it.**

### Keycloak
| Account type | Username               | Password |
|--------------|------------------------|----------|
| Admin        | admin                  | admin    |
| User         | stijnhooft@hotmail.com | test     |
