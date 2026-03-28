# README – Multi-Drone Fire Response System - Iteration 4

## Overview

This project simulates a fire response system that uses multiple drones to handle incidents across different zones. The system consists of three independent processes communicating via UDP: a central Scheduler, multiple Drone Subsystems, and a Fire Incident Subsystem that reads zone and event data from CSV files.

## Core Idea

Each drone operates as an independent process coordinated by a central scheduler over UDP. The scheduler assigns drones to fire zones based on availability and workload, ensuring fires are addressed efficiently across multiple zones.

## Main Components

### 1. SchedulerUDP

The central coordinator that listens on port 7000. It:
- Receives zone definitions and fire events from the FireIncidentSubsystem
- Maintains a queue of pending fire events
- Tracks all registered drones (position, agent level, state, workload)
- Assigns events to available drones using a workload-balancing algorithm
- Detects faults via timeouts and status messages
- Handles hard faults by marking drones offline and requeuing events with faults stripped
- Logs soft faults without disrupting mission flow
- Updates the GUI with drone positions and zone statuses

### 2. DroneSubsystem

Each drone runs as its own process with a unique ID. Drones:
- Register with the scheduler by sending a READY message
- Implement a state machine with the following states: IDLE, TAKEOFF, EN_ROUTE, EXTINGUISHING, RETURNING, REFILLING, FAULTED
- Simulate movement between zones with position updates
- Track agent levels and request refills when empty
- Communicate all state changes to the scheduler via UDP
- Inject faults based on event type:
  - NOZZLE_FAULT: Sends FAULTED status, sets hardFaulted = true
  - STUCK_IN_FLIGHT: Pauses mid-flight, sends periodic EN_ROUTE status, recovers after 3 seconds
  - CORRUPTED_MESSAGE: Sends malformed message, sets hardFaulted = true

### 3. FireIncidentSubsystem

Reads two CSV files and sends their contents to the scheduler:
- Zone file: Defines rectangular zones with coordinates
- Event file: Contains timed fire events with severity levels (Low=10L, Moderate=20L, High=30L), as well as a Fault column for fault injection
Events are sent in real-time respecting time intervals from the file.

### 4. FireGUI

Provides a visual interface showing:
- All defined zones as rectangles
- Drone positions as red dots moving in real-time
- Zone highlighting when a fire is active
- Drone status panels showing ID, state, current zone, and agent level
- FAULTED status displayed for hard faults
- System log panel

## Fault Handling

| Fault Type | Behavior | Scheduler Action | GUI Display |
|------------|----------|------------------|-------------|
| NOZZLE_FAULT | Drone sends FAULTED status, hardFaulted = true | Mark drone offline, requeue event with fault stripped | FAULTED |
| STUCK_IN_FLIGHT | Pauses 3 seconds, sends periodic EN_ROUTE | Soft fault logged, no requeue | EN_ROUTE (DELAYED) |
| CORRUPTED_MESSAGE | Sends malformed message, hardFaulted = true | Log unknown message, mark drone offline, requeue event | FAULTED |

## Timeout Configuration

| Timeout | Value | Purpose |
|---------|-------|---------|
| EN_ROUTE_WARNING_MS | 3500ms | Soft fault threshold (delay detected) |
| EN_ROUTE_TIMEOUT_MS | 8000ms | Hard fault threshold (drone considered offline) |
| EXTINGUISH_PROGRESS_TIMEOUT_MS | 3200ms | Nozzle fault detection during extinguishing |

## Communication Protocol

All communication uses UDP with string-based messages:

Drone to Scheduler:
- READY,\<id\>,\<x\>,\<y\>,\<agent\>
- DRONE_STATUS,\<id\>,\<state\>,\<agent\>
- DRONE_POS,\<id\>,\<x\>,\<y\>
- DRONE_ARRIVED,\<id\>,\<zoneId\>
- DRONE_COMPLETE,\<id\>,\<zoneId\>

Scheduler to Drone:
- ASSIGN,\<time\>,\<zoneId\>,\<type\>,\<severity\>,\<x\>,\<y\>,\<faultType\>
- NO_TASK

FireIncident to Scheduler:
- ZONE,\<id\>,\<x1\>,\<y1\>,\<x2\>,\<y2\>
- EVENT,\<time\>,\<zoneId\>,\<type\>,\<severity\>,\<x\>,\<y\>,\<faultType\>

## Multi-Drone Coordination

- Multiple drones can be active at once
- Each drone handles a different zone
- Drones update their status (busy/available) in real time
- The scheduler dynamically reassigns drones as they finish tasks
- Workload balancing ensures no single drone is overloaded
- After a hard fault, the scheduler reassigns the event to another drone with the fault cleared

## State Machine

The drone subsystem implements a state machine using the State pattern. Each state represents a distinct operational mode with specific behaviors and valid transitions.

States: <br>
IDLE → TAKEOFF → EN_ROUTE → EXTINGUISHING → RETURNING → REFILLING → IDLE
<br>
all states may transition to FAULTED or SOFT_FAULTED in the event of a hard or soft fault, respectively.

Events:
<br>
ASSIGNMENT_RECEIVED: New mission assigned by scheduler
<br>
TAKEOFF_DONE: Takeoff sequence complete
<br>
ARRIVED_AT_ZONE: Drone has reached the fire zone
<br>
AGENT_EMPTY: Firefighting agent depleted
<br>
FIRE_DONE: Fire has been extinguished
<br>
RETURNED_TO_BASE: Drone has landed at base
<br>
REFILL_DONE: Refill process complete

All other event/state combinations are ignored with appropriate logging (private ignore method).

## Testing

### Unit Tests (JUnit)

| Test File | Tests |
|-----------|-------|
| FaultTypeTest.java | FaultType.fromString() parsing, case sensitivity, invalid input handling |
| FireEventTest.java | Fault field storage, toString() includes fault type |
| DroneSubsystemTest.java | NOZZLE_FAULT sets hardFaulted, STUCK_IN_FLIGHT does not, CORRUPTED_MESSAGE sets hardFaulted |
| SchedulerTest.java | Fault stripping, soft/hard fault thresholds, event requeuing |
| EventLoggerTest.java | Log file creation, appending, format, clear functionality |

### Integration Testing

All components function together via UDP communication. To run integration tests:

1. Start SchedulerUDP
2. Start FireIncidentSubsystem (parses sample_event_file.csv & sample_zone_file.csv)
3. Start DroneSubsystem with ID 1
4. Start DroneSubsystem with ID 2

Expected results:
- All components communicate via UDP without any crashes
- Drones register with the scheduler and receive assignments
- GUI updates with drone positions and zone statuses
- Multiple drones operate concurrently without interference

Test files used for integration testing:
- `sample_zone_file.csv` - Zones' Information
- `sample_event_file.csv` - Events' Information

### Acceptance Testing

The system demonstrates proper functioning in the presence of faults using testing files TEST_CASE_EVENT_1-4 and TEST_CASE_ZONE:

Test Case Event x | Fault Type | Zone | Expected Behavior | Status |
|------------|------|-------------------|--------|
TEST_CASE_EVENT_1 | NONE | 1 | Drone extinguishes fire normally | Pass |
TEST_CASE_EVENT_2 | STUCK_IN_FLIGHT | 2 | Soft fault logged, drone recovers, mission completes | Pass |
TEST_CASE_EVENT_3 | NOZZLE_FAULT | 3 | Hard fault, drone offline, event requeued to another drone | Pass |
TEST_CASE_EVENT_4 | CORRUPTED_MESSAGE | 7 | Malformed message logged, drone offline, event requeued | Pass |

## Team Responsibilities

### Iteration 4:
Kalid - GUI updates + revisions to fault handling
<br>
Halden - helped with fault detection & handling, Unit Tests, README.md
<br>
Jesse - Fault Injection, GUI fixes from iteration 3 & Diagrams
<br>
Sarvesh - Comprehensive Timestamp Logging for events (EventLogger)
<br>
Chukwuemeka - Fault Detection & Handling system

### Iteration 3:
Kalid - Updated FireIncidentSubsystem to send events over UDP + event timing with TIME_FACTOR, FireGUI updates: real-time position updates, zone status display, multiple drone tracking
<br>
Halden - UML Class Diagram and README.txt
<br>
Jesse - Implemented DroneStateMachine with State pattern, UDP implementation and message parsing/handling

### Iteration 2:
Kalid - GUI implementation
<br>
Halden - functionality and scheduling
<br>
Jesse - Diagrams and state machine

### Iteration 1:
Kalid - Scheduler and Drone System
<br>
Halden - FireZones and FireEvents
<br>
Jesse - FireSubsystem and diagrams
<br>
Mohamed - GUI and Unit Testing

## Setup Instructions

1. Compile all Java files
2. Ensure CSV files are in the working directory
3. Start SchedulerUDP first
4. Start FireIncidentSubsystem second
5. Start DroneSubsystem instances with IDs: java DroneSubsystem 1, java DroneSubsystem 2, etc.

## Test Files Included

- sample_zone_file.csv
- sample_event_file.csv
- TEST_CASE_ZONE.csv
- TEST_CASE_EVENT_1.csv
- TEST_CASE_EVENT_2.csv
- TEST_CASE_EVENT_3.csv
- TEST_CASE_EVENT_4.csv
- FaultTypeTest.java
- FireEventTest.java
- DroneSubsystemTest.java
- SchedulerTest.java
- EventLoggerTest.java