# Task (back-end)

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
### Keycloak
| Account type | Username               | Password |
|--------------|------------------------|----------|
| Admin        | admin                  | admin    |
| User         | stijnhooft@hotmail.com | test     |
