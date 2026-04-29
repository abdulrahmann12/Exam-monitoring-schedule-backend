# Uni-Guard Schedules — Backend API Documentation

> **Version:** 1.0.0 · **Last Updated:** April 25, 2026  
> **Audience:** Frontend / Integration Engineers

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Base Configuration](#2-base-configuration)
3. [Authentication](#3-authentication)
4. [People Module](#4-people-module)
5. [Rooms Module](#5-rooms-module)
6. [Time Slots Module](#6-time-slots-module)
7. [Assignments Module](#7-assignments-module)
8. [Dashboard Module](#8-dashboard-module)
9. [Settings Module](#9-settings-module)
10. [Pagination & Sorting](#10-pagination--sorting)
11. [Error Handling](#11-error-handling)
12. [Frontend Integration Guide](#12-frontend-integration-guide)

---

## 1. System Overview

Uni-Guard Schedules is a **production-grade exam invigilation scheduling backend** built with Spring Boot. It provides persistent storage and authoritative business rule enforcement for an exam scheduling workflow.

### Core Modules

| Module | Purpose |
|---|---|
| **Authentication** | Single-admin JWT login and session resolution |
| **People** | Manage Chief Invigilators and Invigilators with weekday availability |
| **Rooms** | Manage exam rooms with capacity and staffing requirements |
| **Time Slots** | Manage reusable exam session templates (Morning, Afternoon, etc.) |
| **Assignments** | Assign rooms, chiefs, and invigilators to specific exam dates |
| **Dashboard** | Aggregated metrics for the home screen |
| **Settings** | System-wide branding and exam period configuration |

### Key Business Rules Enforced by the Backend

- A **Chief Invigilator** can supervise at most **2 rooms** in the same time slot on the same date
- An **Invigilator** can be assigned to only **1 room** per time slot per date
- Staff must be **available** (by weekday) on the exam date
- A room cannot be double-booked in the same slot on the same date
- Deleting a person or room that has existing assignments is **rejected**

---

## 2. Base Configuration

| Property | Value |
|---|---|
| **Base URL** | `http://localhost:8081` |
| **API Prefix** | `/api` |
| **Full Base URL** | `http://localhost:8081/api` |
| **Authentication** | JWT Bearer Token |
| **Content-Type** | `application/json` |
| **Date Format** | `YYYY-MM-DD` (ISO 8601) |
| **Time Format** | `HH:mm:ss` (24-hour) |
| **Timestamp Format** | `YYYY-MM-DDTHH:mm:ss.SSSZ` (UTC) |

### Sending the Token

Every protected endpoint requires this header:

```
Authorization: Bearer <your_jwt_token>
```

> **Only `/api/auth/login` is public.** All other endpoints require a valid token.

---

## 3. Authentication

### 3.1 Login

Authenticate as admin and receive a JWT token.

**`POST /api/auth/login`**

**Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
  "email": "admin@uniguard.local",
  "password": "ChangeMe123!"
}
```

**Response `200 OK`:**
```json
{
  "accessToken": "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJhZG1pbkB1bmlndWFyZC5sb2NhbCIsImlhdCI6...",
  "tokenType": "Bearer",
  "expiresAt": "2026-05-02T19:42:17.109Z",
  "email": "admin@uniguard.local"
}
```

| Field | Type | Description |
|---|---|---|
| `accessToken` | string | JWT token — store and attach to all future requests |
| `tokenType` | string | Always `"Bearer"` |
| `expiresAt` | ISO timestamp | Token expiry time (UTC) — default 7 days |
| `email` | string | Authenticated admin email |

**Possible Errors:**

| Status | Message |
|---|---|
| `401` | `Invalid email or password.` |
| `400` | Validation error (missing email or password) |

---

### 3.2 Get Current Session

Verify the token and resolve the current authenticated user. Call this on app startup to check if the user is still logged in.

**`GET /api/auth/me`**

**Headers:**
```
Authorization: Bearer <token>
```

**Response `200 OK`:**
```json
{
  "email": "admin@uniguard.local",
  "roles": ["ROLE_ADMIN"]
}
```

**Possible Errors:**

| Status | Message |
|---|---|
| `401` | `Invalid authentication token` (token expired or invalid) |

---

## 4. People Module

Base path: `/api/people`

### 4.1 Create Person

**`POST /api/people`**

**Headers:**
```
Authorization: Bearer <token>
Content-Type: application/json
```

**Request Body:**
```json
{
  "name": "Dr. Amina Hassan",
  "department": "Mathematics",
  "role": "CHIEF_INVIGILATOR",
  "availableDays": ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | ✅ | Full name (max 160 chars) |
| `department` | string | ✅ | Department name (max 160 chars) |
| `role` | enum | ✅ | `CHIEF_INVIGILATOR` or `INVIGILATOR` |
| `availableDays` | array | ✅ | Any of: `Sun` `Mon` `Tue` `Wed` `Thu` `Fri` `Sat` |

**Response `201 Created`:**
```json
{
  "id": "03831726-da3e-4e7f-8234-15d94a71fb2c",
  "name": "Dr. Amina Hassan",
  "department": "Mathematics",
  "role": "CHIEF_INVIGILATOR",
  "availableDays": ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"],
  "totalAssignments": 0,
  "active": true,
  "maxParallelRooms": 2,
  "createdAt": "2026-04-25T19:50:06.349Z",
  "updatedAt": "2026-04-25T19:50:06.349Z"
}
```

| Field | Description |
|---|---|
| `id` | UUID — use this in all assignment requests |
| `role` | `CHIEF_INVIGILATOR` → `maxParallelRooms: 2`, `INVIGILATOR` → `maxParallelRooms: 1` |
| `totalAssignments` | Auto-incremented workload counter |
| `active` | `true` by default; `false` = soft-deleted |

**Possible Errors:**

| Status | Message |
|---|---|
| `400` | Validation error (missing required field) |
| `401` | Unauthorized |

---

### 4.2 Get All People (Paginated)

**`GET /api/people`**

**Headers:**
```
Authorization: Bearer <token>
```

**Query Parameters:**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Page index (zero-based) |
| `size` | int | `20` | Items per page |
| `sortBy` | string | `name` | Sort field: `id`, `name`, `department`, `role`, `totalAssignments` |
| `direction` | string | `ASC` | `ASC` or `DESC` |
| `role` | enum | — | Filter by `CHIEF_INVIGILATOR` or `INVIGILATOR` |
| `department` | string | — | Partial match on department name |
| `name` | string | — | Partial match on name |

**Example Request:**
```
GET /api/people?page=0&size=10&sortBy=name&direction=ASC&role=CHIEF_INVIGILATOR
```

**Response `200 OK`:**
```json
{
  "content": [
    {
      "id": "03831726-da3e-4e7f-8234-15d94a71fb2c",
      "name": "Dr. Amina Hassan",
      "department": "Mathematics",
      "role": "CHIEF_INVIGILATOR",
      "availableDays": ["Sun", "Mon", "Tue", "Wed", "Thu"],
      "totalAssignments": 3,
      "active": true,
      "maxParallelRooms": 2,
      "createdAt": "2026-04-25T19:50:06.349Z",
      "updatedAt": "2026-04-25T19:50:06.349Z"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "currentPage": 0,
  "size": 20,
  "first": true,
  "last": true
}
```

---

### 4.3 Update Person

**`PUT /api/people/{id}`**

**Headers:**
```
Authorization: Bearer <token>
Content-Type: application/json
```

**Path Parameter:** `id` — UUID of the person

**Request Body:** Same structure as [Create Person](#41-create-person)

**Response `200 OK`:** Same structure as Create Person response

**Possible Errors:**

| Status | Message |
|---|---|
| `404` | `Person with id {id} was not found.` |
| `409` | `Person '{name}' is currently assigned as a chief invigilator and cannot change to a non-chief role.` |
| `409` | `Updated availability does not cover an existing assignment on {day} ({date}).` |

---

### 4.4 Delete Person

**`DELETE /api/people/{id}`**

**Headers:**
```
Authorization: Bearer <token>
```

**Path Parameter:** `id` — UUID of the person

**Response `204 No Content`** (empty body)

**Possible Errors:**

| Status | Message |
|---|---|
| `404` | `Person with id {id} was not found.` |
| `409` | `Person '{name}' is referenced by existing assignments and cannot be deleted.` |

---

## 5. Rooms Module

Base path: `/api/rooms`

### 5.1 Create Room

**`POST /api/rooms`**

**Headers:**
```
Authorization: Bearer <token>
Content-Type: application/json
```

**Request Body:**
```json
{
  "name": "Hall A",
  "capacity": 30,
  "type": "MEDIUM",
  "minInvigilators": 2
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | ✅ | Unique room name (max 120 chars) |
| `capacity` | int | ✅ | Seating capacity (min 1) |
| `type` | enum | ✅ | `SMALL`, `MEDIUM`, or `LARGE` |
| `minInvigilators` | int | ✅ | Minimum required invigilators (min 1) |

**Response `201 Created`:**
```json
{
  "id": "236be40a-5d17-4a13-9c56-43e713a4eccd",
  "name": "Hall A",
  "capacity": 30,
  "type": "MEDIUM",
  "minInvigilators": 2,
  "active": true,
  "createdAt": "2026-04-25T19:50:06.538Z",
  "updatedAt": "2026-04-25T19:50:06.538Z"
}
```

**Possible Errors:**

| Status | Message |
|---|---|
| `409` | `Room '{name}' already exists.` |
| `400` | Validation error |

---

### 5.2 Get All Rooms (Paginated)

**`GET /api/rooms`**

**Query Parameters:**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Page index |
| `size` | int | `20` | Items per page |
| `sortBy` | string | `name` | Sort field: `id`, `name`, `capacity`, `type`, `minInvigilators` |
| `direction` | string | `ASC` | `ASC` or `DESC` |
| `type` | enum | — | Filter by `SMALL`, `MEDIUM`, or `LARGE` |
| `name` | string | — | Partial match on room name |
| `minCapacity` | int | — | Minimum capacity filter |
| `maxCapacity` | int | — | Maximum capacity filter |

**Example Request:**
```
GET /api/rooms?page=0&size=10&sortBy=name&type=LARGE
```

**Response `200 OK`:**
```json
{
  "content": [
    {
      "id": "236be40a-5d17-4a13-9c56-43e713a4eccd",
      "name": "Hall A",
      "capacity": 30,
      "type": "MEDIUM",
      "minInvigilators": 2,
      "active": true,
      "createdAt": "2026-04-25T19:50:06.538Z",
      "updatedAt": "2026-04-25T19:50:06.538Z"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "currentPage": 0,
  "size": 20,
  "first": true,
  "last": true
}
```

---

### 5.3 Update Room

**`PUT /api/rooms/{id}`**

**Path Parameter:** `id` — UUID of the room

**Request Body:** Same structure as [Create Room](#51-create-room)

**Response `200 OK`:** Same structure as Create Room response

**Possible Errors:**

| Status | Message |
|---|---|
| `404` | `Room with id {id} was not found.` |
| `409` | `Room '{name}' already exists.` |

---

### 5.4 Delete Room

**`DELETE /api/rooms/{id}`**

**Response `204 No Content`**

**Possible Errors:**

| Status | Message |
|---|---|
| `404` | `Room with id {id} was not found.` |
| `409` | `Room '{name}' is referenced by existing assignments and cannot be deleted.` |

---

## 6. Time Slots Module

Base path: `/api/slots`

> **Important:** A Time Slot is a **reusable template** (e.g. "Morning Session 08:00–11:00"). It has **no date**. The exam date is attached at the Assignment level.

### 6.1 Create Time Slot

**`POST /api/slots`**

**Headers:**
```
Authorization: Bearer <token>
Content-Type: application/json
```

**Request Body:**
```json
{
  "label": "Morning Session",
  "startTime": "08:00:00",
  "endTime": "11:00:00",
  "sortOrder": 1
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `label` | string | — | Display name (max 120 chars), e.g. `"Morning Session"` |
| `startTime` | string | ✅ | 24-hour format `HH:mm:ss` |
| `endTime` | string | ✅ | 24-hour format `HH:mm:ss` — must be after `startTime` |
| `sortOrder` | int | — | UI ordering (default `0`) |

**Response `201 Created`:**
```json
{
  "id": "99b8e95a-358c-4db3-a6bd-df35b9a6f772",
  "label": "Morning Session",
  "startTime": "08:00:00",
  "endTime": "11:00:00",
  "sortOrder": 1,
  "active": true,
  "createdAt": "2026-04-25T19:50:06.604Z",
  "updatedAt": "2026-04-25T19:50:06.604Z"
}
```

**Possible Errors:**

| Status | Message |
|---|---|
| `400` | `End time must be after start time.` |
| `400` | Validation error |

---

### 6.2 Get All Time Slots (Paginated)

**`GET /api/slots`**

**Query Parameters:**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Page index |
| `size` | int | `20` | Items per page |
| `sortBy` | string | `sortOrder` | Sort field: `id`, `label`, `startTime`, `endTime`, `sortOrder` |
| `direction` | string | `ASC` | `ASC` or `DESC` |
| `label` | string | — | Partial match on label |
| `activeOnly` | boolean | — | `true` to return only active slots |

**Example Request:**
```
GET /api/slots?page=0&size=20&sortBy=sortOrder&direction=ASC&activeOnly=true
```

**Response `200 OK`:**
```json
{
  "content": [
    {
      "id": "99b8e95a-358c-4db3-a6bd-df35b9a6f772",
      "label": "Morning Session",
      "startTime": "08:00:00",
      "endTime": "11:00:00",
      "sortOrder": 1,
      "active": true,
      "createdAt": "2026-04-25T19:50:06.604Z",
      "updatedAt": "2026-04-25T19:50:06.604Z"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "currentPage": 0,
  "size": 20,
  "first": true,
  "last": true
}
```

---

### 6.3 Update Time Slot

**`PUT /api/slots/{id}`**

**Request Body:** Same structure as [Create Time Slot](#61-create-time-slot)

**Response `200 OK`:** Same structure as Create Time Slot response

**Possible Errors:**

| Status | Message |
|---|---|
| `404` | `Time slot with id {id} was not found.` |
| `400` | `End time must be after start time.` |

---

### 6.4 Delete Time Slot

**`DELETE /api/slots/{id}`**

**Response `204 No Content`**

**Possible Errors:**

| Status | Message |
|---|---|
| `404` | `Time slot with id {id} was not found.` |
| `409` | `Time slot '{label}' at {time} is referenced by existing assignments and cannot be deleted. Use deactivate instead.` |

> **Tip:** If the slot has existing assignments, use **Deactivate** instead of Delete.

---

### 6.5 Deactivate Time Slot

Soft-deactivates a slot. It remains visible in assignment history but cannot be used for new assignments.

**`PATCH /api/slots/{id}/deactivate`**

**Headers:**
```
Authorization: Bearer <token>
```

**Response `200 OK`:** Same as Time Slot object with `"active": false`

---

## 7. Assignments Module

Base path: `/api/assignments`

> An **Assignment** represents one room assigned to one time slot on one specific exam date, including the Chief Invigilator and all Invigilator positions.

### 7.1 Create Assignment

**`POST /api/assignments`**

**Headers:**
```
Authorization: Bearer <token>
Content-Type: application/json
```

**Request Body:**
```json
{
  "examDate": "2026-05-15",
  "roomId": "236be40a-5d17-4a13-9c56-43e713a4eccd",
  "timeSlotId": "99b8e95a-358c-4db3-a6bd-df35b9a6f772",
  "subjectName": "Calculus I",
  "subjectCode": "MATH101",
  "chiefInvigilatorId": "03831726-da3e-4e7f-8234-15d94a71fb2c",
  "locked": false,
  "source": "MANUAL",
  "invigilatorIds": [
    "155b6f69-6304-4120-957d-3be065f987b9",
    null
  ]
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `examDate` | string | ✅ | ISO date `YYYY-MM-DD` — the actual exam date |
| `roomId` | UUID | ✅ | ID of the room |
| `timeSlotId` | UUID | ✅ | ID of the time slot template |
| `subjectName` | string | — | Subject name (max 160 chars) |
| `subjectCode` | string | — | Subject code (max 40 chars) |
| `chiefInvigilatorId` | UUID | — | Must have role `CHIEF_INVIGILATOR`; `null` = unassigned |
| `locked` | boolean | ✅ | `true` = locked row (skip in partial regeneration) |
| `source` | enum | — | `GENERATED`, `MANUAL`, or `MIXED` (default: `MANUAL`) |
| `invigilatorIds` | array | ✅ | Ordered list of invigilator UUIDs; `null` = empty position |

> **Invigilator positions:** Send `null` in the array to create an empty (placeholder) slot. The first `room.minInvigilators` positions are marked `required: true` automatically.

**Response `201 Created`:**
```json
{
  "id": "6481a758-f978-4c2a-a5b0-f9e9b2e5f8ba",
  "examDate": "2026-05-15",
  "roomId": "236be40a-5d17-4a13-9c56-43e713a4eccd",
  "roomName": "Hall A",
  "timeSlotId": "99b8e95a-358c-4db3-a6bd-df35b9a6f772",
  "slotLabel": "Morning Session",
  "startTime": "08:00:00",
  "endTime": "11:00:00",
  "subjectName": "Calculus I",
  "subjectCode": "MATH101",
  "chiefInvigilatorId": "03831726-da3e-4e7f-8234-15d94a71fb2c",
  "chiefInvigilatorName": "Dr. Amina Hassan",
  "locked": false,
  "generationVersion": 0,
  "source": "MANUAL",
  "invigilators": [
    {
      "id": "8fdb9e8a-cb85-4479-b63b-48478b6d266e",
      "invigilatorId": "155b6f69-6304-4120-957d-3be065f987b9",
      "invigilatorName": "Khalid Nasser",
      "positionIndex": 0,
      "required": true,
      "createdAt": "2026-04-25T19:50:06.818Z"
    }
  ],
  "createdAt": "2026-04-25T19:50:06.812Z",
  "updatedAt": "2026-04-25T19:50:06.812Z"
}
```

| Field | Description |
|---|---|
| `slotLabel` | Human-readable label from the time slot template |
| `generationVersion` | Incremented by the scheduling engine on each generation run |
| `source` | Tracks origin: `GENERATED`, `MANUAL`, or `MIXED` |
| `invigilators[].positionIndex` | Order of the invigilator in the room |
| `invigilators[].required` | `true` = mandatory position (within `minInvigilators`) |

**Possible Errors:**

| Status | Message |
|---|---|
| `404` | `Room with id {id} was not found.` |
| `404` | `Time slot with id {id} was not found.` |
| `404` | `Person with id {id} was not found.` |
| `409` | `Room '{name}' already has an assignment for the selected time slot.` |
| `409` | `Person '{name}' is not a chief invigilator.` |
| `409` | `Person '{name}' is not an invigilator.` |
| `409` | `Chief invigilator '{name}' is already supervising the maximum number of rooms for this time slot.` |
| `409` | `Invigilator '{name}' is already assigned in this time slot.` |
| `409` | `Chief invigilator '{name}' is unavailable on {day} ({date}).` |
| `409` | `Invigilator '{name}' is unavailable on {day} ({date}).` |
| `400` | `Duplicate invigilator ids are not allowed in the same room assignment.` |

---

### 7.2 Get Assignments (Paginated + Filtered)

**`GET /api/assignments`**

**Headers:**
```
Authorization: Bearer <token>
```

**Query Parameters:**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Page index |
| `size` | int | `20` | Items per page |
| `sortBy` | string | `examDate` | Sort field: `id`, `examDate`, `subjectName`, `subjectCode`, `locked`, `source`, `generationVersion`, `timeSlot.startTime`, `room.name` |
| `direction` | string | `ASC` | `ASC` or `DESC` |
| `slotId` | UUID | — | Filter by time slot ID |
| `roomId` | UUID | — | Filter by room ID |
| `locked` | boolean | — | Filter by lock state |
| `fromDate` | string | — | Filter from date (`YYYY-MM-DD`) |
| `toDate` | string | — | Filter to date (`YYYY-MM-DD`) |

**Example Requests:**
```
# All assignments for a specific slot
GET /api/assignments?slotId=99b8e95a-358c-4db3-a6bd-df35b9a6f772

# Assignments for a date range
GET /api/assignments?fromDate=2026-05-01&toDate=2026-05-31&sortBy=examDate

# Only locked assignments
GET /api/assignments?locked=true&sortBy=room.name
```

**Response `200 OK`:**
```json
{
  "content": [
    {
      "id": "6481a758-f978-4c2a-a5b0-f9e9b2e5f8ba",
      "examDate": "2026-05-15",
      "roomId": "236be40a-5d17-4a13-9c56-43e713a4eccd",
      "roomName": "Hall A",
      "timeSlotId": "99b8e95a-358c-4db3-a6bd-df35b9a6f772",
      "slotLabel": "Morning Session",
      "startTime": "08:00:00",
      "endTime": "11:00:00",
      "subjectName": "Calculus I",
      "subjectCode": "MATH101",
      "chiefInvigilatorId": "03831726-da3e-4e7f-8234-15d94a71fb2c",
      "chiefInvigilatorName": "Dr. Amina Hassan",
      "locked": false,
      "generationVersion": 0,
      "source": "MANUAL",
      "invigilators": [
        {
          "id": "8fdb9e8a-cb85-4479-b63b-48478b6d266e",
          "invigilatorId": "155b6f69-6304-4120-957d-3be065f987b9",
          "invigilatorName": "Khalid Nasser",
          "positionIndex": 0,
          "required": true,
          "createdAt": "2026-04-25T19:50:06.818Z"
        }
      ],
      "createdAt": "2026-04-25T19:50:06.812Z",
      "updatedAt": "2026-04-25T19:50:06.812Z"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "currentPage": 0,
  "size": 20,
  "first": true,
  "last": true
}
```

---

### 7.3 Update Assignment

Replaces the entire assignment (chief, invigilators, subject, lock state, source).

**`PUT /api/assignments/{id}`**

**Path Parameter:** `id` — UUID of the assignment

**Request Body:** Same structure as [Create Assignment](#71-create-assignment)

**Response `200 OK`:** Same structure as Create Assignment response

**Possible Errors:** Same as Create Assignment, plus:

| Status | Message |
|---|---|
| `404` | `Assignment with id {id} was not found.` |

---

### 7.4 Delete Assignment

**`DELETE /api/assignments/{id}`**

**Response `204 No Content`**

> Workload counters (`totalAssignments`) for all previously assigned staff are automatically decremented.

**Possible Errors:**

| Status | Message |
|---|---|
| `404` | `Assignment with id {id} was not found.` |

---

## 8. Dashboard Module

Base path: `/api/dashboard`

### 8.1 Get Summary

Returns aggregated metrics for the Dashboard page.

**`GET /api/dashboard/summary`**

**Headers:**
```
Authorization: Bearer <token>
```

**Response `200 OK`:**
```json
{
  "totalChiefInvigilators": 5,
  "totalInvigilators": 20,
  "totalRooms": 12,
  "totalAssignments": 48,
  "topAssignedStaff": [
    {
      "id": "03831726-da3e-4e7f-8234-15d94a71fb2c",
      "name": "Dr. Amina Hassan",
      "role": "CHIEF_INVIGILATOR",
      "totalAssignments": 8
    },
    {
      "id": "155b6f69-6304-4120-957d-3be065f987b9",
      "name": "Khalid Nasser",
      "role": "INVIGILATOR",
      "totalAssignments": 6
    }
  ]
}
```

| Field | Description |
|---|---|
| `totalChiefInvigilators` | Count of active Chief Invigilators |
| `totalInvigilators` | Count of active Invigilators |
| `totalRooms` | Count of active Rooms |
| `totalAssignments` | Total room assignment records |
| `topAssignedStaff` | Top 10 most-assigned active staff members |

---

## 9. Settings Module

Base path: `/api/settings`

Settings are **singleton** — there is exactly one settings record in the system. It is auto-created on first startup.

### 9.1 Get Settings

**`GET /api/settings`**

**Headers:**
```
Authorization: Bearer <token>
```

**Response `200 OK`:**
```json
{
  "id": "00000000-0000-0000-0000-000000000001",
  "systemName": "Uni-Guard Schedules",
  "appTagline": "Exam Invigilation Planning",
  "logoUrl": null,
  "theme": "LIGHT",
  "universityName": "University of Example",
  "department": "Faculty of Sciences",
  "examPeriod": "Spring Semester 2026"
}
```

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Always `00000000-0000-0000-0000-000000000001` |
| `systemName` | string | App title shown in sidebar and exports |
| `appTagline` | string | Subtitle shown under app name |
| `logoUrl` | string / null | URL of the logo image |
| `theme` | enum | `LIGHT` or `DARK` |
| `universityName` | string | University name shown in exports |
| `department` | string / null | Department name |
| `examPeriod` | string | Current exam period label |

---

### 9.2 Update Settings

**`PUT /api/settings`**

**Headers:**
```
Authorization: Bearer <token>
Content-Type: application/json
```

**Request Body:**
```json
{
  "systemName": "Uni-Guard Schedules",
  "appTagline": "Exam Invigilation Planning",
  "logoUrl": "https://example.com/logo.png",
  "theme": "DARK",
  "universityName": "University of Example",
  "department": "Faculty of Sciences",
  "examPeriod": "Spring Semester 2026"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `systemName` | string | ✅ | Max 160 chars |
| `appTagline` | string | — | Max 200 chars |
| `logoUrl` | string | — | Max 500 chars |
| `theme` | enum | ✅ | `LIGHT` or `DARK` |
| `universityName` | string | ✅ | Max 200 chars |
| `department` | string | — | Max 160 chars |
| `examPeriod` | string | ✅ | Max 120 chars |

**Response `200 OK`:** Same structure as Get Settings response

---

## 10. Pagination & Sorting

All list endpoints follow a consistent pagination contract.

### Request Parameters

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `20` | Items per page (max 100) |
| `sortBy` | string | varies | Field name to sort by (see each endpoint) |
| `direction` | string | `ASC` | `ASC` or `DESC` |

### Response Shape

```json
{
  "content": [ /* array of items */ ],
  "totalElements": 42,
  "totalPages": 5,
  "currentPage": 0,
  "size": 10,
  "first": true,
  "last": false
}
```

| Field | Description |
|---|---|
| `content` | Array of items for the current page |
| `totalElements` | Total records matching the filter |
| `totalPages` | Total number of pages |
| `currentPage` | Current page index (zero-based) |
| `size` | Page size used |
| `first` | `true` if this is the first page |
| `last` | `true` if this is the last page |

---

## 11. Error Handling

All errors follow a consistent JSON structure:

```json
{
  "timestamp": "2026-04-25T19:50:06.349Z",
  "status": 404,
  "error": "Not Found",
  "message": "Person with id 03831726-da3e-4e7f-8234-15d94a71fb2c was not found.",
  "path": "/api/people/03831726-da3e-4e7f-8234-15d94a71fb2c",
  "validationErrors": []
}
```

### Validation Errors (400)

When request body fields fail validation, `validationErrors` is populated:

```json
{
  "timestamp": "2026-04-25T19:50:06.349Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/people",
  "validationErrors": [
    {
      "field": "name",
      "message": "must not be blank"
    },
    {
      "field": "role",
      "message": "must not be null"
    }
  ]
}
```

### HTTP Status Codes Reference

| Status | Meaning | When |
|---|---|---|
| `200` | OK | Successful GET or PUT |
| `201` | Created | Successful POST |
| `204` | No Content | Successful DELETE |
| `400` | Bad Request | Validation failure or invalid input |
| `401` | Unauthorized | Missing, expired, or invalid JWT token |
| `404` | Not Found | Resource does not exist |
| `409` | Conflict | Business rule violation (duplicate, in-use, role mismatch, unavailability) |
| `500` | Internal Server Error | Unexpected server error |

---

## 12. Frontend Integration Guide

### 12.1 Storing the Token

After a successful login, store the token in `localStorage` (or a secure cookie for production):

```js
// After login
localStorage.setItem('token', response.data.accessToken)
localStorage.setItem('tokenExpiresAt', response.data.expiresAt)

// On logout
localStorage.removeItem('token')
localStorage.removeItem('tokenExpiresAt')
```

---

### 12.2 Axios Setup (Recommended)

Create a single Axios instance with the token attached automatically:

```js
// src/lib/api.js
import axios from 'axios'

const api = axios.create({
  baseURL: 'http://localhost:8081/api',
  headers: { 'Content-Type': 'application/json' },
})

// Attach token to every request
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Handle 401 globally (redirect to login)
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default api
```

---

### 12.3 Session Check on App Startup

Call `/api/auth/me` when the app loads to verify the stored token is still valid:

```js
// src/App.jsx
import { useEffect, useState } from 'react'
import api from './lib/api'

function App() {
  const [ready, setReady] = useState(false)
  const [authenticated, setAuthenticated] = useState(false)

  useEffect(() => {
    const token = localStorage.getItem('token')
    if (!token) {
      setReady(true)
      return
    }
    api.get('/auth/me')
      .then(() => setAuthenticated(true))
      .catch(() => localStorage.removeItem('token'))
      .finally(() => setReady(true))
  }, [])

  if (!ready) return <div>Loading...</div>
  return authenticated ? <MainApp /> : <LoginPage />
}
```

---

### 12.4 Login Flow

```js
import api from './lib/api'

async function login(email, password) {
  const { data } = await api.post('/auth/login', { email, password })
  localStorage.setItem('token', data.accessToken)
  localStorage.setItem('tokenExpiresAt', data.expiresAt)
  return data
}
```

---

### 12.5 Common API Calls

```js
// ── People ────────────────────────────────────────────────────────────────────
const getPeople = (params) => api.get('/people', { params })
// params: { page, size, sortBy, direction, role, department, name }

const createPerson = (body) => api.post('/people', body)
const updatePerson = (id, body) => api.put(`/people/${id}`, body)
const deletePerson = (id) => api.delete(`/people/${id}`)

// ── Rooms ─────────────────────────────────────────────────────────────────────
const getRooms = (params) => api.get('/rooms', { params })
const createRoom = (body) => api.post('/rooms', body)
const updateRoom = (id, body) => api.put(`/rooms/${id}`, body)
const deleteRoom = (id) => api.delete(`/rooms/${id}`)

// ── Time Slots ────────────────────────────────────────────────────────────────
const getSlots = (params) => api.get('/slots', { params })
const createSlot = (body) => api.post('/slots', body)
const updateSlot = (id, body) => api.put(`/slots/${id}`, body)
const deleteSlot = (id) => api.delete(`/slots/${id}`)
const deactivateSlot = (id) => api.patch(`/slots/${id}/deactivate`)

// ── Assignments ───────────────────────────────────────────────────────────────
const getAssignments = (params) => api.get('/assignments', { params })
// params: { page, size, sortBy, slotId, roomId, locked, fromDate, toDate }

const createAssignment = (body) => api.post('/assignments', body)
const updateAssignment = (id, body) => api.put(`/assignments/${id}`, body)
const deleteAssignment = (id) => api.delete(`/assignments/${id}`)

// ── Dashboard ─────────────────────────────────────────────────────────────────
const getDashboardSummary = () => api.get('/dashboard/summary')

// ── Settings ──────────────────────────────────────────────────────────────────
const getSettings = () => api.get('/settings')
const updateSettings = (body) => api.put('/settings', body)
```

---

### 12.6 CORS Configuration

The backend allows cross-origin requests from:

| Origin | Purpose |
|---|---|
| `http://localhost:5173` | Vite dev server (default) |
| `http://localhost:3000` | Create React App dev server |
| `http://localhost:8080` | Alternative dev port |

> For production, update the `app.cors.allowed-origins` values in `application.properties` to match your deployed frontend domain.

---

### 12.7 Enum Reference

#### `role` (Person)
| Value | Description |
|---|---|
| `CHIEF_INVIGILATOR` | Can supervise up to 2 rooms per slot |
| `INVIGILATOR` | Can be in 1 room per slot only |

#### `availableDays` (Person)
| Value | Day |
|---|---|
| `Sun` | Sunday |
| `Mon` | Monday |
| `Tue` | Tuesday |
| `Wed` | Wednesday |
| `Thu` | Thursday |
| `Fri` | Friday |
| `Sat` | Saturday |

#### `type` (Room)
| Value | Description |
|---|---|
| `SMALL` | Small room |
| `MEDIUM` | Medium room |
| `LARGE` | Large hall |

#### `theme` (Settings)
| Value | Description |
|---|---|
| `LIGHT` | Light mode |
| `DARK` | Dark mode |

#### `source` (Assignment)
| Value | Description |
|---|---|
| `GENERATED` | Created by scheduling engine |
| `MANUAL` | Created manually by admin |
| `MIXED` | Partially edited after generation |

---

*End of API Documentation*
