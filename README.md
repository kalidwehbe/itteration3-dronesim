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
- Updates the GUI with drone positions and zone statuses

### 2. DroneSubsystem

Each drone runs as its own process with a unique ID. Drones:
- Register with the scheduler by sending a READY message
- Implement a state machine with the following states: IDLE, TAKEOFF, EN_ROUTE, EXTINGUISHING, RETURNING, REFILLING
- Simulate movement between zones with position updates
- Track agent levels and request refills when empty
- Communicate all state changes to the scheduler via UDP

### 3. FireIncidentSubsystem

Reads two CSV files and sends their contents to the scheduler:
- Zone file: Defines rectangular zones with coordinates
- Event file: Contains timed fire events with severity levels (Low=10L, Moderate=20L, High=30L), as well as fault events now
Events are sent in real-time respecting time intervals from the file.

### 4. FireGUI

Provides a visual interface showing:
- All defined zones as rectangles
- Drone positions as red dots moving in real-time
- Zone highlighting when a fire is active
- Drone status panels showing ID, state, current zone, and agent level
- System log panel

## Communication Protocol

All communication uses UDP with string-based messages:

Drone to Scheduler:
- READY,\<id\>,\<x\>,\<y\>,\<agent\>
- DRONE_STATUS,\<id\>,\<state\>,\<agent\>
- DRONE_POS,\<id\>,\<x\>,\<y\>
- DRONE_ARRIVED,\<id\>,\<zoneId\>
- DRONE_COMPLETE,\<id\>,\<zoneId\>

Scheduler to Drone:
- ASSIGN,\<time\>,\<zoneId\>,\<type\>,\<severity\>,\<x\>,\<y\>
- NO_TASK

FireIncident to Scheduler:
- ZONE,\<id\>,\<x1\>,\<y1\>,\<x2\>,\<y2\>
- EVENT,\<time\>,\<zoneId\>,\<type\>,\<severity\>,\<x\>,\<y\>

## Multi-Drone Coordination

- Multiple drones can be active at once
- Each drone handles a different zone
- Drones update their status (busy/available) in real time
- The scheduler dynamically reassigns drones as they finish tasks
- Workload balancing ensures no single drone is overloaded

## State Machine

The drone subsystem implements a state machine using the State pattern. Each state represents a distinct operational mode with specific behaviors and valid transitions.

States: <br>
IDLE → TAKEOFF → EN_ROUTE → EXTINGUISHING → RETURNING → REFILLING → IDLE

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

## Team Responsibilities

### Iteration 4:
Kalid -
<br>
Halden -
<br>
Jesse - Fault Injection, UML Class Diagram, GUI fixes from iteration 3
<br>
Sarvesh -
<br>
Chukwuemeka -

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
- TEST_CASE_EVENT.csv
- Iteration3_UML_Class_Diagram.pdf
