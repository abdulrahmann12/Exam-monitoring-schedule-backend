# Uni-Guard Schedules: Complete Project Analysis and Backend Planning

## Document Intent

This document is a single, complete, raw analysis of the current Uni-Guard Schedules project. It consolidates the frontend architecture review, page-by-page breakdown, business logic inspection, data flow analysis, backend planning, and production gap assessment.

The project analyzed is a React + Vite + TypeScript + shadcn UI application that currently operates as a frontend-first scheduling system for exam invigilation planning.

No backend code was implemented as part of this analysis. This document reflects the current state of the repository as analyzed.

---

## 1. Executive Summary

Uni-Guard Schedules is an exam invigilation planning application intended to help an administrator assign Chief Invigilators and Invigilators to exam rooms across dates and time slots. The application already contains real scheduling logic in the frontend. It is not just a UI mockup.

The current frontend already implements:

- schedule generation
- schedule validation
- manual assignment and replacement
- drag-and-drop invigilator swapping
- lock and partial regeneration workflows
- dynamic invigilator slot management
- PDF export
- Excel export
- branding customization
- light and dark theme support
- timeline search and staff profile views

The project is best described as a strong prototype or internal tool rather than a production system. The main missing pieces are:

- persistent backend storage
- backend ownership of scheduling and validation rules
- authentication
- audit trail
- deletion safeguards
- meaningful automated test coverage

The architecture direction is clear: the frontend should remain the interactive planning client, while the backend should become the authoritative owner of persistence, validation, generation, and access control.

---

## 2. Technology Stack and Current Technical Shape

### Frontend Stack

| Area | Technology |
|---|---|
| Frontend framework | React 18 |
| Build tool | Vite |
| Language | TypeScript |
| Routing | React Router |
| UI library | shadcn UI with Radix primitives |
| Styling | Tailwind CSS |
| Drag and drop | dnd-kit |
| Export | jsPDF, jspdf-autotable, xlsx |
| Query and caching scaffold | TanStack React Query |
| Testing | Vitest, Testing Library |
| Backend scaffold present | Supabase client |

### Global App Providers

The app is bootstrapped in src/main.tsx and src/App.tsx.

Global wrappers currently include:

- QueryClientProvider
- BrandingProvider
- TooltipProvider
- Toaster providers
- UniGuardProvider
- BrowserRouter

Important architectural note: React Query is configured but not used for domain state yet. The actual scheduling data lives in a custom React context inside src/lib/uniguard/store.tsx.

### Route Structure

| Route | Page |
|---|---|
| / | Dashboard |
| /scheduler | Scheduler |
| /timeline | Timeline |
| /people | People |
| /rooms | Rooms |
| /settings | Settings |
| * | NotFound |

### Major Domain Files

| File | Responsibility |
|---|---|
| src/lib/uniguard/store.tsx | Core in-memory domain state and mutation commands |
| src/lib/uniguard/engine.ts | Schedule generation algorithm |
| src/lib/uniguard/constraintEngine.ts | Validation rules and picker eligibility logic |
| src/lib/uniguard/types.ts | Domain model types and helper rules |
| src/lib/uniguard/mockData.ts | Seed data for staff, rooms, and slots |
| src/lib/uniguard/exporters.ts | Client-side PDF and Excel export |
| src/lib/branding/BrandingProvider.tsx | Branding and theme state with localStorage persistence |
| src/components/uniguard/ScheduleGrid.tsx | Main schedule editing surface |
| src/components/uniguard/StaffPicker.tsx | Staff selection UI based on scheduling constraints |
| src/integrations/supabase/client.ts | Backend scaffold, currently unused |

---

## 3. Application Purpose

### Problem the System Solves

The system solves the operational problem of assigning invigilation staff to exam rooms in a way that respects role constraints, room staffing requirements, and weekday availability while trying to distribute workload fairly.

At a business level, the system answers questions such as:

- Which Chief Invigilator should supervise which rooms for a given exam slot?
- Which Invigilators should be assigned to each room?
- Is a person available on the chosen day?
- Is any person double-booked in the same time slot?
- Does each room have enough staff based on its required minimum?
- How can the plan be balanced across available staff?
- How can staff assignments be reviewed and adjusted manually?
- How can the schedule be exported in an official-looking document?

### What the Application Does Today

Today, the application allows the user to:

- manage staff and their weekday availability
- manage rooms and staffing requirements
- use a set of slot templates as reusable exam windows
- generate staff assignments for a selected date and slot
- manually edit generated assignments
- validate the resulting schedule in real time
- review the full master timeline
- export the schedule to PDF and Excel
- customize branding and theme

### What the Application Does Not Yet Do

The current application does not yet provide:

- persistent storage of schedules, staff, rooms, or slots
- login or access control
- multi-user collaboration
- audit logging
- date-specific availability exceptions
- overlap validation based on actual start and end times across different slot templates
- backend-enforced business rules

---

## 4. Overall Workflow of the Current Application

The intended workflow is:

1. Configure staff members and their weekday availability.
2. Configure rooms and the minimum invigilator requirement for each room.
3. Use the predefined time slots or edit their time windows.
4. Select a date and one time slot.
5. Select which rooms are participating in that slot.
6. Generate assignments automatically.
7. Review completeness and conflicts in the schedule grid.
8. Manually adjust assignments as needed.
9. Inspect the timeline view across dates.
10. Export the schedule.

The most important unit of planning in the current design is not "the whole schedule". It is a single combination of:

- exam date
- time slot
- set of room assignments for that date and slot

This matters because the backend should model scheduling operations at that same date-slot slice level.

---

## 5. Frontend Architecture Overview

### Primary State Ownership

The main application state is held in src/lib/uniguard/store.tsx.

This store owns:

- staff
- rooms
- slots
- schedule entries
- history for undo support

The state is initialized from:

- seedStaff() in src/lib/uniguard/mockData.ts
- seedRooms() in src/lib/uniguard/mockData.ts
- SLOTS constant in src/lib/uniguard/mockData.ts
- an empty schedule array

### Branding and Theme State

Branding is separate from scheduling data and is managed in src/lib/branding/BrandingProvider.tsx.

This provider controls:

- app name
- app tagline
- university
- department
- exam period
- logoDataUrl
- theme

This provider persists to localStorage. It is the only real persistence in the current project.

### Layout and Navigation

The shared shell is handled by:

- src/components/uniguard/AppLayout.tsx
- src/components/uniguard/AppSidebar.tsx

The shell provides:

- page framing
- sidebar navigation
- page headers
- export menu
- theme toggle
- settings link
- placeholder search bar
- placeholder notification bell

The header search input and notification bell are UI-only. They do not connect to any domain logic.

---

## 6. Pages and Modules Breakdown

## 6.1 Dashboard

### Main Files

- src/pages/uniguard/Dashboard.tsx
- src/components/uniguard/StatCard.tsx

### What It Displays

The Dashboard shows:

- total number of Chief Invigilators
- total number of Invigilators
- total number of rooms
- total assignment count
- conflict count based on schedule validation
- quick action cards linking to key modules
- top assigned staff members by workload

### What Actions the User Can Perform

The user can:

- open the scheduler
- navigate to people management
- navigate to room management
- navigate to timeline view
- jump to conflict resolution via the scheduler

### What Data It Depends On

The Dashboard reads:

- staff
- rooms
- schedule
- validateEntry function from the store

### Important Behavior

- Conflict counting is based on validating each schedule entry.
- Total assignments are derived from staff.totalAssignments.
- Top assigned staff are sorted by workload.
- The page is a summary surface only. It does not mutate data directly.

---

## 6.2 People Management

### Main Files

- src/pages/uniguard/People.tsx
- src/components/uniguard/DayBadges.tsx
- src/components/uniguard/StaffProfileDialog.tsx

### What It Displays

The People page has two tabs:

- Chief Invigilators
- Invigilators

Each row shows:

- name
- department
- available working days
- workload bar
- delete action

The profile dialog shows:

- role
- department
- internal ID
- working days
- workload vs team average
- over-assigned indicator
- assignment history

### What Actions the User Can Perform

The user can:

- add a staff member
- choose the role
- set department
- choose available weekdays
- remove a staff member
- open the profile modal
- inspect past assignments for that person

### What Data It Depends On

The People page depends on:

- staff
- schedule
- rooms
- slots

### Important Behavior

- Availability is defined only by weekday, not by date.
- Added staff are created entirely in the frontend.
- Workload uses the derived totalAssignments field.
- Removing staff does not automatically clean up schedule entries that reference them.

---

## 6.3 Rooms Management

### Main Files

- src/pages/uniguard/Rooms.tsx
- src/lib/uniguard/types.ts

### What It Displays

The Rooms page shows:

- room name
- capacity
- room size type
- staffing guideline
- delete action

### What Actions the User Can Perform

The user can:

- add a room
- set room name
- set capacity
- override minimum invigilators
- remove a room

### What Data It Depends On

The Rooms page depends on:

- rooms
- capacity helper functions

### Important Behavior

- The default rule is minInvigilatorsForCapacity(capacity).
- Large rooms are identified at capacity >= 40.
- The minimum invigilator requirement is editable at creation time.
- Removing a room does not automatically clean up schedule entries that reference it.

---

## 6.4 Scheduler / Generator

### Main Files

- src/pages/uniguard/Scheduler.tsx
- src/components/uniguard/ScheduleGrid.tsx
- src/components/uniguard/StaffPicker.tsx

### What It Displays

The Scheduler page shows:

- date picker
- selectable slot templates
- slot edit dialog for start and end time
- room selection list
- generate and regenerate actions
- export action
- the schedule grid for the selected date and slot

### What Actions the User Can Perform

At page level, the user can:

- choose a date
- choose a slot
- edit the selected slot time window
- select rooms
- generate a new schedule
- partially regenerate unlocked rows
- export

Within the ScheduleGrid, the user can:

- assign a Chief Invigilator manually
- assign Invigilators manually
- clear assignments
- add a new room into the current slot
- add or remove invigilator positions
- edit subject name and subject code per room assignment
- drag and drop invigilators between positions
- lock or unlock a room assignment
- undo the last schedule change
- reset a slot to the last generated baseline

### What Data It Depends On

The Scheduler depends on:

- rooms
- slots
- selected date
- selected slot
- current schedule entry for that date and slot
- generation logic
- validation logic
- manual mutation commands

### Important Behavior

- The page does real scheduling work; it is not only a form.
- Subjects are effectively stored at room-assignment level.
- Locked rows are preserved in partial regeneration.
- Existing valid assignments are reused when possible.
- The grid is the heart of the operational workflow.

---

## 6.5 Timeline / Master Schedule View

### Main Files

- src/pages/uniguard/Timeline.tsx

### What It Displays

The Timeline page groups scheduled items by date and displays:

- time range
- room
- subject and code
- Chief Invigilator
- Invigilators

### What Actions the User Can Perform

The user can:

- search by staff name
- search by subject name
- search by subject code
- search by room name
- search by weekday or date
- open staff profiles from assignments
- export the schedule

### What Data It Depends On

The Timeline depends on:

- full schedule
- staff lookup map
- room lookup map
- slot lookup map

### Important Behavior

- Search filters assignments before grouping.
- Assignment subject values override slot-level default subject values.
- Shared chiefs are visually labeled.
- This is a read-heavy reporting surface.

---

## 6.6 Settings

### Main Files

- src/pages/uniguard/Settings.tsx
- src/lib/branding/BrandingProvider.tsx

### What It Displays

The Settings page contains:

- logo upload and preview
- branding fields
- theme selectors
- exported document header fields

### What Actions the User Can Perform

The user can:

- upload a logo
- remove the logo
- save branding
- reset branding to defaults
- toggle theme
- save document header settings

### What Data It Depends On

The Settings page depends entirely on branding context state.

### Important Behavior

- Branding changes affect the sidebar, browser title, and exports.
- Theme selection is persisted locally.
- Logo is stored as a data URL in localStorage.

---

## 7. Domain Model Analysis

Main file: src/lib/uniguard/types.ts

### Current Types

#### Day

Current valid Day values are:

- Sun
- Mon
- Tue
- Wed
- Thu

This is already a limitation because Friday and Saturday are not first-class values.

#### Role

Current roles are:

- CHIEF_INVIGILATOR
- INVIGILATOR

#### Staff

Fields:

- id
- name
- role
- department
- workingDays
- totalAssignments

#### Room

Fields:

- id
- name
- capacity
- minInvigilators

#### Slot

Fields:

- id
- date optional
- startTime
- endTime
- subjectName optional
- subjectCode optional

Important note: subjectName and subjectCode on Slot are explicitly marked as deprecated defaults.

#### Assignment

Fields:

- roomId
- slotId
- chiefInvigilatorId
- invigilatorIds
- locked
- sharedChief optional
- subjectName optional
- subjectCode optional

This structure makes each room assignment the real unit of scheduling.

#### ScheduleEntry

Fields:

- date
- slotId
- day
- assignments
- lastGeneratedAssignments optional

This means a schedule entry represents one specific date and one specific time slot.

### Important Modeling Decisions

- The app does not currently model an Exam entity separately.
- The schedule is room-assignment centric.
- Subjects are stored on assignments, not in a standalone exam table.
- Staff availability is recurring by weekday.
- Invigilator positions are modeled as an array, not child records.

These decisions matter when planning the backend because they indicate the current UX assumptions.

---

## 8. Feature Analysis

## 8.1 Schedule Generation Logic

### Status

Fully implemented in the frontend.

### Main Files

- src/lib/uniguard/engine.ts
- src/lib/uniguard/store.tsx
- src/pages/uniguard/Scheduler.tsx

### What It Does

The generator:

- accepts selected rooms, current day, slot, and optional prior assignments
- preserves locked room assignments
- reuses valid existing assignments when possible
- fills missing or invalid chief and invigilator positions
- assigns staff by lowest workload first
- flags missing staffing or invalid results as conflicts or incompleteness

### Important Logic Details

- Chiefs can supervise up to two rooms in the same slot.
- Invigilators can supervise only one room in the same slot.
- Candidates are filtered by working day availability.
- Fairness uses totalAssignments plus new assignments created during the current generation run.
- The generator applies final validation after constructing assignments.

### Assessment

This is real business logic and should move to the backend or to a shared package owned by the backend.

---

## 8.2 Constraint Handling

### Status

Fully implemented in the frontend.

### Main Files

- src/lib/uniguard/constraintEngine.ts

### What It Validates

- missing Chief Invigilator
- insufficient invigilator count per room
- role mismatch
- weekday unavailability
- Chief Invigilator two-room limit violations
- Invigilator same-slot double booking

### Validation States

The validator returns one of:

- VALID
- INCOMPLETE
- CONFLICT

INCOMPLETE is used when the issue is staffing capacity only.
CONFLICT is used for more serious rule violations.

### Assessment

This is core business logic and must be backend-owned in production.

---

## 8.3 Manual Assignment and Replacement

### Status

Fully implemented in the frontend.

### Main Files

- src/components/uniguard/ScheduleGrid.tsx
- src/components/uniguard/StaffPicker.tsx
- src/lib/uniguard/store.tsx

### What It Does

The manual assignment flow allows:

- choosing a Chief Invigilator
- choosing an Invigilator
- clearing assignments
- replacing assignments
- swapping invigilators by drag and drop

### Important Behavior

- Before applying a change, the store validates the resulting slot assignments.
- Blocking issues are rejected.
- Capacity-related incompleteness can still exist.
- The staff picker categorizes candidates into free, partially busy, and unavailable.

### Assessment

The interaction is frontend UI, but final rule enforcement must live on the backend.

---

## 8.4 Dynamic Invigilator Slots

### Status

Fully implemented in the frontend.

### Main Files

- src/components/uniguard/ScheduleGrid.tsx
- src/lib/uniguard/store.tsx

### What It Does

The user can:

- add an extra invigilator position to a room assignment
- remove an invigilator position from a room assignment

### Important Behavior

- A room can temporarily exceed its minimum staffing requirement.
- Removing a slot can drop the room below the minimum.
- The UI warns when removal will fall below the minimum required count.

### Assessment

This is a real feature and should be modeled as child records in the backend.

---

## 8.5 Export: PDF and Excel

### Status

Fully implemented client-side.

### Main Files

- src/lib/uniguard/exporters.ts
- src/components/uniguard/ExportDialog.tsx

### PDF Export Behavior

The PDF export:

- uses jsPDF
- creates a landscape A4 document
- includes branded header values
- optionally includes logo
- groups data by exported date(s)
- includes room, subject, chief, and invigilators

### Excel Export Behavior

The Excel export:

- uses xlsx
- creates a full schedule sheet
- creates a workload summary sheet
- summarizes assignments by role count

### Assessment

The export functionality is real and already useful. It can stay frontend-only initially, but backend export is preferable for production-grade official documents.

---

## 8.6 Dark / Light Mode

### Status

Implemented.

### Main Files

- src/lib/branding/BrandingProvider.tsx
- src/index.css

### What It Does

- stores theme in localStorage
- toggles the dark class on the root element
- changes the visual design tokens in CSS

### Important Note

src/components/ui/sonner.tsx uses next-themes useTheme(), but the app does not wrap itself with next-themes ThemeProvider. The app theme is managed by a custom provider instead.

This means toast theming is slightly inconsistent with the actual application theming architecture.

---

## 8.7 Branding System

### Status

Implemented.

### Main Files

- src/lib/branding/BrandingProvider.tsx
- src/pages/uniguard/Settings.tsx
- src/components/uniguard/AppSidebar.tsx
- src/lib/uniguard/exporters.ts

### What It Does

Branding currently affects:

- application name in the sidebar
- application tagline
- browser title
- exported document header
- optional logo in sidebar and export

### Assessment

This is a real feature, but it is only persisted locally on the current device. In production, branding should move to backend settings.

---

## 8.8 Additional Implemented Features Worth Noting

### Lock and Partial Regeneration

Implemented via:

- src/lib/uniguard/store.tsx
- src/pages/uniguard/Scheduler.tsx

Locked room assignments are preserved during partial regeneration.

### Undo and Reset-to-Generated

Implemented via:

- src/lib/uniguard/store.tsx

Undo stores up to 10 schedule snapshots.
Reset-to-generated restores the last generated baseline for a slot.

### Timeline Search

Implemented via:

- src/pages/uniguard/Timeline.tsx

Supports searching by:

- staff name
- room name
- subject name
- subject code
- date
- day

### Staff Workload Analytics

Implemented via:

- src/components/uniguard/StaffProfileDialog.tsx

Displays assignment counts, working days, comparison to average, and assignment history.

---

## 9. Data Flow Analysis

## 9.1 Where Data Comes From Today

Current data sources are:

- seeded mock staff
- seeded mock rooms
- seeded slot templates
- empty in-memory schedule
- branding and theme from localStorage

The scheduling data does not come from any API or database.

## 9.2 How State Is Managed

The app uses two main context layers:

### UniGuardProvider

Handles:

- staff
- rooms
- slots
- schedule
- mutation functions
- validation helpers
- undo history

### BrandingProvider

Handles:

- app branding
- theme
- document title
- localStorage persistence for branding and theme

## 9.3 Current Mutation Flow

The current mutation flow is:

1. A page or component calls a store function.
2. The store may call the scheduling engine or validator.
3. The store updates React state.
4. The UI re-renders.
5. Derived values such as workload counts are recomputed.

## 9.4 What Happens on Refresh

On full page refresh:

- staff reset to mock data
- rooms reset to mock data
- slots reset to mock data
- schedule resets to empty
- undo history resets to empty
- branding persists
- theme persists

### Why This Happens

The scheduling store uses in-memory useState initialization and does not persist its state.

## 9.5 React Query and Supabase Status

### React Query

React Query is initialized in src/App.tsx but not used for actual domain data.

### Supabase

The Supabase client exists in src/integrations/supabase/client.ts but is not imported anywhere in the domain flow.

The generated database types file currently describes an empty schema.

### Assessment

The project has backend scaffolding but no backend integration.

---

## 10. Scheduling Algorithm Analysis

Main file: src/lib/uniguard/engine.ts

## 10.1 Inputs

The generator accepts:

- roomIds
- rooms
- staff
- day
- slotId
- existing assignments
- defaultSubject

## 10.2 Internal Strategy

The generator performs the following steps:

1. Build room lookup maps.
2. Build existing assignment maps.
3. Separate locked assignments from reusable unlocked assignments.
4. Seed current usage counts from locked assignments.
5. Track chief room counts.
6. Track invigilators already used in the slot.
7. Track workload delta during the current generation run.
8. For each selected room:
   - preserve locked assignments
   - reuse previous chief or invigilators if still valid
   - fill missing or invalid chief assignment
   - fill missing or invalid invigilator positions
9. Derive sharedChief flags.
10. Validate the final assignments.
11. Return assignments and de-duplicated conflict messages.

## 10.3 Fairness Logic

Staff are sorted by:

- current totalAssignments
- current-run delta
- alphabetical name order as a tie-breaker

This is a lightweight fairness strategy and is good enough for a first production version.

## 10.4 Output Shape

The output includes:

- assignments
- staffUpdates
- conflicts

staffUpdates are currently not used for persistence. Workload is recomputed separately in the store.

## 10.5 Why This Logic Is Reusable

This file is largely pure domain logic and can be moved into a backend service or shared domain package with minimal change.

---

## 11. Constraint Validation Analysis

Main file: src/lib/uniguard/constraintEngine.ts

## 11.1 Validation Rules Detected

The validator enforces:

- each room should have a Chief Invigilator
- each room should have at least room.minInvigilators filled invigilator positions
- assigned chief must have role CHIEF_INVIGILATOR
- assigned invigilators must have role INVIGILATOR
- assigned people must be available on the relevant weekday
- a chief cannot exceed the two-room limit in one slot
- an invigilator cannot be used in more than one room in one slot

## 11.2 Issue Types

Detected issue types are:

- availability
- concurrency
- capacity
- role

## 11.3 State Computation

The validator distinguishes between:

- VALID when there are no issues
- INCOMPLETE when the only issues are capacity-related
- CONFLICT when any non-capacity issue exists

This is a good business distinction and should remain in the backend design.

## 11.4 Picker Tier Logic

The same file also produces staff picker tiers:

- free
- partiallyBusy
- unavailable with reasons

This logic is currently UI-facing but depends on the same domain rules.

---

## 12. Assignment Rules and Business Policies Detected

The current codebase implies the following business rules:

| Rule | Current Status |
|---|---|
| Chief must have Chief role | Enforced |
| Invigilator must have Invigilator role | Enforced |
| Chief must be available on that weekday | Enforced |
| Invigilator must be available on that weekday | Enforced |
| Chief can supervise up to 2 rooms in same slot | Enforced |
| Invigilator can supervise only 1 room in same slot | Enforced |
| Each room needs one Chief to be complete | Enforced |
| Each room needs at least minInvigilators invigilators to be complete | Enforced |
| A slot may contain multiple rooms | Enforced |
| Different rooms in same slot may have different subjects | Enforced |
| Date-specific exceptions to weekly availability | Not supported |
| Validation across overlapping slot times | Not supported |
| More than one chief per room | Not supported |

---

## 13. Hidden Issues and Observed Risks in the Current Code

## 13.1 Friday and Saturday Handling Defect

The dayOfDate helper in src/lib/uniguard/store.tsx maps JavaScript weekdays into an internal Day type that only supports Sun through Thu.

If the selected date is Friday or Saturday, the helper falls back to Sun.

### Impact

- availability checks can be incorrect
- generated assignments for Friday or Saturday can be wrong
- validation results can be wrong

This is not just a missing feature. It is a logic defect.

## 13.2 Slot Overlap Risk

Slot times are editable, but concurrency rules only consider slot membership, not actual time overlap.

### Impact

Two different slot templates could overlap in clock time and still allow the same person to be scheduled in both.

Backend validation should eventually consider actual slot times if slot editing remains flexible.

## 13.3 Orphaned References After Delete

Removing staff or rooms only updates the source arrays. It does not repair existing schedule entries.

### Impact

- schedule rows can reference deleted IDs
- downstream UI can show missing values or incomplete data
- backend delete logic must prevent or handle this explicitly

## 13.4 Inconsistent Theme Integration

src/components/ui/sonner.tsx reads next-themes useTheme(), but the app theme is managed by a custom BrandingProvider instead of next-themes ThemeProvider.

### Impact

Toast theming may not align fully with the rest of the app's theme state.

## 13.5 Placeholder UI Surfaces

The following are present but not functionally implemented:

- header search bar
- notification bell

These are harmless from an architecture perspective but should not be mistaken for implemented features.

## 13.6 Unused or Scaffolded Surfaces

Observed unused or scaffolded items include:

- src/integrations/supabase/client.ts
- src/integrations/supabase/types.ts
- React Query domain usage
- src/components/NavLink.tsx is present but not used by the sidebar
- src/App.css still contains default Vite starter styles and is not meaningfully part of the product UI

---

## 14. Backend Planning

## 14.1 Backend Responsibilities

### What Should Remain in the Frontend

- layout, routing, and visual components
- date and room selection UI
- drag-and-drop interactions
- dialog and popover state
- search and filtering
- optimistic editing state
- device-level theme preference

### What Must Move to the Backend

- staff persistence
- room persistence
- slot template persistence
- schedule persistence
- settings persistence across devices
- authoritative validation
- authoritative generation
- assignment writes
- lock state persistence
- authentication
- audit trail
- deletion safeguards

### Architectural Principle

The backend should not be only a CRUD layer. It should be the authoritative scheduling service.

---

## 14.2 Backend Behavior Model

The most natural transaction boundary for this application is one date-slot slice.

Recommended backend flow for a scheduling command:

1. Load the target date and slot.
2. Load all room assignments for that date-slot.
3. Load relevant people and availability data.
4. Apply generation or validation logic.
5. Persist the updated date-slot slice atomically.

This matches the current frontend workflow and minimizes migration complexity.

---

## 14.3 Proposed Database Design

### Core Entities

- People
- PeopleAvailability
- Rooms
- TimeSlots
- RoomAssignments
- InvigilatorAssignments
- Settings
- AdminUsers

### Recommended Table Design

#### People

| Field | Notes |
|---|---|
| id | UUID primary key |
| full_name | required |
| role | CHIEF_INVIGILATOR or INVIGILATOR |
| department | required |
| active | soft delete support |
| max_parallel_rooms | 2 for chiefs, 1 for invigilators by policy |
| created_at | timestamp |
| updated_at | timestamp |

#### PeopleAvailability

| Field | Notes |
|---|---|
| id | UUID primary key |
| person_id | foreign key to People |
| weekday | should support Sun through Sat |
| is_available | boolean |

Unique key: person_id + weekday

#### Rooms

| Field | Notes |
|---|---|
| id | UUID primary key |
| name | required |
| capacity | required |
| min_invigilators | required |
| active | soft delete support |
| created_at | timestamp |
| updated_at | timestamp |

#### TimeSlots

| Field | Notes |
|---|---|
| id | UUID primary key |
| label | optional display label |
| start_time | required |
| end_time | required |
| sort_order | for UI ordering |
| active | soft delete support |
| created_at | timestamp |
| updated_at | timestamp |

Constraint: start_time < end_time

#### RoomAssignments

| Field | Notes |
|---|---|
| id | UUID primary key |
| exam_date | required |
| time_slot_id | foreign key to TimeSlots |
| room_id | foreign key to Rooms |
| subject_name | required for actual scheduled use |
| subject_code | optional |
| chief_person_id | nullable foreign key to People |
| locked | boolean |
| generation_version | supports reset-to-generated |
| source | generated or manual or mixed |
| created_at | timestamp |
| updated_at | timestamp |

Unique key: exam_date + time_slot_id + room_id

#### InvigilatorAssignments

| Field | Notes |
|---|---|
| id | UUID primary key |
| room_assignment_id | foreign key to RoomAssignments |
| position_index | ordered slot position |
| person_id | nullable foreign key to People |
| required | distinguishes minimum positions from extra positions |
| created_at | timestamp |
| updated_at | timestamp |

Unique key: room_assignment_id + position_index

#### Settings

| Field | Notes |
|---|---|
| id | singleton or tenant-scoped |
| app_name | required |
| app_tagline | optional |
| university | required |
| department | required |
| exam_period | required |
| logo_url | stored asset reference |
| updated_at | timestamp |
| updated_by | admin user reference |

#### AdminUsers

| Field | Notes |
|---|---|
| id | UUID primary key |
| email | unique |
| password_hash | required |
| is_active | boolean |
| created_at | timestamp |
| updated_at | timestamp |
| last_login_at | optional |

### Recommended Supporting Tables

Additional tables that would help production readiness:

- ScheduleGenerationRuns
- AuditLog
- AvailabilityExceptions

### Why Child Records Are Better for Invigilators

The current frontend uses an array of invigilatorIds. In the backend, a separate InvigilatorAssignments table is a better fit because:

- dynamic slot counts are already supported
- ordering matters
- child records are easier to query and update safely
- explicit required versus extra slots can be represented cleanly

---

## 14.4 API Design

### Authentication Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| POST | /auth/login | authenticate admin |
| POST | /auth/logout | end session |
| GET | /auth/me | resolve current session |

### People Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /people | list all people |
| POST | /people | create person |
| GET | /people/:id | get one person |
| PATCH | /people/:id | update person |
| DELETE | /people/:id | deactivate or remove person |
| PUT | /people/:id/availability | replace weekday availability |

### Rooms Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /rooms | list rooms |
| POST | /rooms | create room |
| GET | /rooms/:id | get room |
| PATCH | /rooms/:id | update room |
| DELETE | /rooms/:id | deactivate or remove room |

### Time Slot Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /time-slots | list slot templates |
| POST | /time-slots | create slot template |
| GET | /time-slots/:id | get slot template |
| PATCH | /time-slots/:id | update slot template |
| DELETE | /time-slots/:id | deactivate or remove slot template |

### Settings Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /settings | load branding and export settings |
| PATCH | /settings | update branding and export settings |
| POST | /settings/logo-upload | upload or produce upload URL |

### Schedule Read Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /schedule?from=YYYY-MM-DD&to=YYYY-MM-DD | load date range schedule |
| GET | /schedule/:date/:slotId | load one date-slot entry |
| GET | /timeline?from=YYYY-MM-DD&to=YYYY-MM-DD | timeline-oriented read model |
| GET | /dashboard/summary | dashboard metrics |

### Schedule Command Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| POST | /schedule/generate | generate or regenerate one date-slot slice |
| POST | /schedule/validate | validate a proposed slice without saving |
| POST | /room-assignments | add room to date-slot |
| PATCH | /room-assignments/:id | update subject, chief, or metadata |
| POST | /room-assignments/:id/lock | lock or unlock assignment |
| DELETE | /room-assignments/:id | remove room from slot |
| POST | /room-assignments/:id/invigilator-slots | add invigilator slot |
| PATCH | /invigilator-assignments/:id | change invigilator |
| DELETE | /invigilator-assignments/:id | remove invigilator slot |
| POST | /schedule/:date/:slotId/reset-to-generated | restore generated baseline |

### Export Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /exports/schedule.pdf?mode=single&date=YYYY-MM-DD | export PDF |
| GET | /exports/schedule.xlsx?mode=full | export Excel |

### Recommended Generate Request Shape

Suggested request body:

{
  "examDate": "2026-05-10",
  "timeSlotId": "slot_1",
  "roomIds": ["room_a", "room_b"],
  "partial": true
}

Suggested response should include:

- resulting assignments
- validation state
- issues list
- generationVersion
- updated entry payload

---

## 14.5 Persistence Strategy

### Current Problem

All domain state resets on refresh.

### Target Strategy

The backend should become the source of truth.
The frontend should use React Query for reads and writes.

### Recommended Query Keys

- people
- rooms
- timeSlots
- settings
- scheduleRange
- scheduleEntry(date, slotId)
- dashboardSummary

### Recommended Mutation Types

- create person
- update person
- replace availability
- create room
- update room
- create slot
- update slot
- generate schedule
- assign chief
- assign invigilator
- add or remove invigilator slot
- add or remove room assignment
- update assignment subject
- toggle lock
- update settings

### Sync Model Recommendation

- backend is source of truth
- frontend is cached copy
- use invalidation or optimistic updates after mutations
- add versioning or updated_at fields to protect against stale writes

### Branding Persistence Recommendation

Move branding to backend settings so it is shared across devices.
Theme may remain local browser preference.

---

## 14.6 Authentication Design

### Recommended Model

Use a simple admin login with secure session cookies.

### Suggested Flow

1. Frontend loads.
2. Frontend calls GET /auth/me.
3. If authenticated, render the app.
4. If not authenticated, render login screen.
5. Login sends credentials to POST /auth/login.
6. Backend verifies password hash and sets an httpOnly secure cookie.
7. Protected routes and API calls require that session.
8. POST /auth/logout clears the session.

### Why This Is Enough Initially

The current product behaves like an internal admin tool. A single admin role is enough for the first backend version.

### Supabase Note

If Supabase is used, it can supply authentication and storage, but scheduling and validation should still be treated as backend-owned domain logic, not as browser-only logic.

---

## 15. Gap Analysis

## 15.1 What Is Already Implemented in the Frontend

| Area | Status |
|---|---|
| Multi-page app with routing | implemented |
| Shared layout and sidebar | implemented |
| People management | implemented |
| Room management | implemented |
| Schedule generation | implemented |
| Constraint validation | implemented |
| Manual assignment and replacement | implemented |
| Drag-and-drop invigilator swaps | implemented |
| Dynamic invigilator slots | implemented |
| Lock and partial regenerate workflow | implemented |
| Undo support | implemented |
| Reset-to-generated support | implemented |
| Timeline view and search | implemented |
| Staff profile modal | implemented |
| PDF export | implemented |
| Excel export | implemented |
| Branding settings | implemented |
| Light and dark mode | implemented |
| Local persistence for branding and theme | implemented |

## 15.2 What Is Missing for Production Readiness

| Area | Missing |
|---|---|
| Persistent database | yes |
| API integration | yes |
| Real React Query domain usage | yes |
| Authentication | yes |
| Authorization | yes |
| Backend-owned validation | yes |
| Backend-owned generation | yes |
| Audit trail | yes |
| Delete safeguards | yes |
| Business logic test coverage | yes |
| Overlap validation across slot templates | yes |
| Date-specific availability exceptions | yes |
| Shared branding persistence | yes |
| Real notifications | yes |
| Functional global search | yes |

## 15.3 Severity-Based Risk Summary

| Severity | Risk | Reason |
|---|---|---|
| Critical | Data loss on refresh | staff, rooms, slots, and schedule are not persisted |
| High | No authentication | application has no access control |
| High | Frontend-only rule ownership | backend cannot enforce correctness yet |
| High | Friday and Saturday fallback bug | validation and generation can be wrong on those days |
| High | Orphaned references on delete | current deletes do not protect schedule integrity |
| Medium | No overlap detection by actual times | edited slot windows can produce invalid concurrency behavior |
| Medium | No audit history | no traceability for official scheduling decisions |
| Medium | Minimal test coverage | business rules can regress undetected |
| Medium | Branding stored only locally | cross-device inconsistency |
| Low | Placeholder search bar | not functional |
| Low | Placeholder notification bell | not functional |
| Low | Theme integration inconsistency | toast theming is not aligned with the app's theme provider |
| Low | Unused backend scaffold | maintenance noise until backend is implemented |

---

## 16. Production Readiness Assessment

This project is currently suitable for:

- prototype demonstrations
- internal workflow exploration
- frontend-driven scheduling experiments
- architecture planning for a real backend

This project is not yet suitable for:

- persistent operational use
- multi-device institutional use
- authenticated administrative workflows
- auditable official scheduling management

Approximate readiness assessment:

- prototype readiness: high
- production readiness: low to medium

A reasonable summary is that the application is strong as a feature prototype but not ready as a reliable system of record.

---

## 17. Recommended Implementation Order

### Phase 1: Persistence Foundation

1. Add backend schema for people, availability, rooms, slots, settings, room assignments, and invigilator assignments.
2. Add admin authentication.
3. Load people, rooms, slots, and settings from backend.

### Phase 2: Schedule Persistence

1. Add schedule read endpoints.
2. Add assignment mutation endpoints.
3. Refactor the frontend store to call backend APIs while preserving the current UI-facing surface.

### Phase 3: Backend Rule Ownership

1. Move generation to backend.
2. Move validation to backend.
3. Keep optional client-side hints only for responsiveness.

### Phase 4: Operational Hardening

1. Add audit logging.
2. Add delete safeguards.
3. Add slot overlap validation.
4. Add date-specific availability exceptions.
5. Move export generation to backend if official document generation is required.

### Phase 5: Quality and Testing

1. Add unit tests for generation rules.
2. Add unit tests for validation rules.
3. Add integration tests for scheduling workflows.
4. Add end-to-end tests for the major user journey.

---

## 18. Testing Status

### Current Test Situation

The repository contains:

- src/test/example.test.ts
- src/test/setup.ts
- vitest.config.ts

The current test suite passes, but only one placeholder test exists.

### What This Means

- the repository has test tooling configured correctly
- the business logic is effectively untested
- schedule generation and validation rules need direct unit coverage

---

## 19. Key File Map

| Area | Main Files |
|---|---|
| App bootstrap and routing | src/main.tsx, src/App.tsx |
| Dashboard | src/pages/uniguard/Dashboard.tsx, src/components/uniguard/StatCard.tsx |
| People | src/pages/uniguard/People.tsx, src/components/uniguard/DayBadges.tsx, src/components/uniguard/StaffProfileDialog.tsx |
| Rooms | src/pages/uniguard/Rooms.tsx |
| Scheduler | src/pages/uniguard/Scheduler.tsx, src/components/uniguard/ScheduleGrid.tsx, src/components/uniguard/StaffPicker.tsx |
| Timeline | src/pages/uniguard/Timeline.tsx |
| Settings | src/pages/uniguard/Settings.tsx, src/lib/branding/BrandingProvider.tsx |
| Domain model | src/lib/uniguard/types.ts |
| Store | src/lib/uniguard/store.tsx |
| Scheduling algorithm | src/lib/uniguard/engine.ts |
| Constraint validation | src/lib/uniguard/constraintEngine.ts |
| Mock data | src/lib/uniguard/mockData.ts |
| Export | src/lib/uniguard/exporters.ts, src/components/uniguard/ExportDialog.tsx |
| Backend scaffold | src/integrations/supabase/client.ts, src/integrations/supabase/types.ts |
| Tests | src/test/example.test.ts, src/test/setup.ts, vitest.config.ts |

---

## 20. Final Conclusion

Uni-Guard Schedules is already a meaningful scheduling product on the frontend. It has real scheduling logic, real validation logic, and a complete operational flow for a single user working in one browser session.

Its most important strength is that the domain logic is already fairly well isolated in:

- src/lib/uniguard/engine.ts
- src/lib/uniguard/constraintEngine.ts
- src/lib/uniguard/store.tsx

Its biggest weakness is that the system of record does not yet exist. The browser currently owns everything except branding and theme persistence.

The correct next step is not to redesign the frontend from scratch. The correct next step is to build the backend around the domain model that already exists, then migrate the frontend store from in-memory state to API-backed state in controlled phases.

If implemented that way, the current UI can be preserved, the existing business logic can be reused, and the application can evolve from a capable prototype into a production-ready scheduling platform.