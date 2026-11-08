CREATE DATABASE IF NOT EXISTS kilowhat_db;
USE kilowhat_db;

-- 1. OUTLETS TABLE (Unchanged)
CREATE TABLE IF NOT EXISTS outlets (
    outlet_id VARCHAR(50) PRIMARY KEY,       
    custom_name VARCHAR(100) DEFAULT NULL,   
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- NEW TABLE: 2. GROUPS / OWNERS TABLE
-- This stores the entities you want to cluster items under (e.g., 'Maria', 'Living Room Group')
CREATE TABLE IF NOT EXISTS groups (
    group_id INT AUTO_INCREMENT PRIMARY KEY,
    group_name VARCHAR(100) NOT NULL UNIQUE, -- e.g., "Maria" or "Maria's"
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- MODIFIED TABLE: 3. APPLIANCES TABLE
-- We add a 'group_id' column here to link each hardware plug to its owner/group.
CREATE TABLE IF NOT EXISTS appliances (
    appliance_rfid VARCHAR(50) PRIMARY KEY,  
    custom_name VARCHAR(100) DEFAULT NULL,   -- e.g., 'Fridge', 'Computer'
    group_id INT DEFAULT NULL,                -- Links to the group table
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (group_id) REFERENCES groups(group_id) ON DELETE SET NULL
);

-- 4. SESSIONS TABLE (Unchanged)
-- Notice that the sessions table remains pristine. It still only tracks the raw hardware data.
-- Your Kotlin app will use JOINs to figure out how much power Maria's group used total.
CREATE TABLE IF NOT EXISTS sessions (
    session_id INT AUTO_INCREMENT PRIMARY KEY,
    outlet_id VARCHAR(50),
    appliance_rfid VARCHAR(50),
    start_timestamp DATETIME NOT NULL,
    end_timestamp DATETIME NOT NULL,
    total_wh_consumed DECIMAL(10, 3) NOT NULL,
    FOREIGN KEY (outlet_id) REFERENCES outlets(outlet_id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
