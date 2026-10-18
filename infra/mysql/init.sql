-- Academic Records (MySQL)
-- Authoritative source for courses, assessments, and grades.
-- grades.student_id corresponds to PostgreSQL students(id), but this
-- database does not enforce that relationship.

CREATE TABLE courses (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    title      VARCHAR(200)  NOT NULL,
    credits    INT           NOT NULL,
    department VARCHAR(100)  NOT NULL
);

CREATE TABLE assessments (
    id        INT AUTO_INCREMENT PRIMARY KEY,
    course_id INT           NOT NULL,
    type      VARCHAR(50)   NOT NULL,   -- 'exam' | 'assignment' | 'project'
    weight    DECIMAL(4,2)  NOT NULL,   -- weights per course must sum to 1.00
    FOREIGN KEY (course_id) REFERENCES courses(id)
);

CREATE TABLE grades (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    student_id    INT           NOT NULL,   -- authoritative in PostgreSQL; no FK here
    assessment_id INT           NOT NULL,
    score         DECIMAL(5,2)  NOT NULL,   -- 0.00-100.00
    FOREIGN KEY (assessment_id) REFERENCES assessments(id)
);

-- Final grades are keyed by (student_id, course_id) and replicated in
-- PostgreSQL enrollments.final_grade.
CREATE TABLE final_grades (
    student_id  INT          NOT NULL,   -- authoritative in PostgreSQL; no FK here
    course_id   INT          NOT NULL,
    final_grade DECIMAL(5,2) NOT NULL,
    PRIMARY KEY (student_id, course_id),
    FOREIGN KEY (course_id) REFERENCES courses(id)
);

-- Courses (8)
INSERT INTO courses (id, title, credits, department) VALUES
    (1, 'Introduction to Programming', 6, 'Computer Science'),
    (2, 'Data Structures',             6, 'Computer Science'),
    (3, 'Database Systems',            6, 'Computer Science'),
    (4, 'Algorithms',                  6, 'Computer Science'),
    (5, 'Linear Algebra',              6, 'Mathematics'),
    (6, 'Statistics',                  6, 'Mathematics'),
    (7, 'Circuit Theory',              6, 'Electrical Engineering'),
    (8, 'Software Engineering',        6, 'Computer Science');

-- Assessments (16, 2 per course, weights sum to 1.00 per course)
INSERT INTO assessments (id, course_id, type, weight) VALUES
    -- Course 1: Introduction to Programming
    ( 1, 1, 'assignment', 0.30),
    ( 2, 1, 'exam',       0.70),
    -- Course 2: Data Structures
    ( 3, 2, 'assignment', 0.30),
    ( 4, 2, 'exam',       0.70),
    -- Course 3: Database Systems
    ( 5, 3, 'project',    0.40),
    ( 6, 3, 'exam',       0.60),
    -- Course 4: Algorithms
    ( 7, 4, 'assignment', 0.25),
    ( 8, 4, 'exam',       0.75),
    -- Course 5: Linear Algebra
    ( 9, 5, 'assignment', 0.30),
    (10, 5, 'exam',       0.70),
    -- Course 6: Statistics
    (11, 6, 'assignment', 0.40),
    (12, 6, 'exam',       0.60),
    -- Course 7: Circuit Theory
    (13, 7, 'assignment', 0.30),
    (14, 7, 'exam',       0.70),
    -- Course 8: Software Engineering
    (15, 8, 'project',    0.50),
    (16, 8, 'exam',       0.50);

-- Grades (96)
-- Organised by student. Only assessments for enrolled courses are included.
-- Enrollment truth is in PostgreSQL enrollments; these rows are consistent with it.
--
-- Assessment-to-course map (for cross-reference):
--   1,2 = course 1 | 3,4 = course 2 | 5,6 = course 3  | 7,8  = course 4
--   9,10= course 5 | 11,12= course 6 | 13,14= course 7 | 15,16= course 8
INSERT INTO grades (student_id, assessment_id, score) VALUES
    -- Student 1 (Alice Chen) - courses 1,2,3
    (1,  1, 84.00), (1,  2, 80.00),
    (1,  3, 86.00), (1,  4, 81.00),
    (1,  5, 82.00), (1,  6, 83.00),
    -- Student 2 (Bob Martinez) - courses 1,2,4
    (2,  1, 76.00), (2,  2, 74.00),
    (2,  3, 78.00), (2,  4, 73.00),
    (2,  7, 75.00), (2,  8, 76.00),
    -- Student 3 (Charlotte Williams) - courses 2,3,8
    (3,  3, 90.00), (3,  4, 87.00),
    (3,  5, 89.00), (3,  6, 88.00),
    (3, 15, 91.00), (3, 16, 86.00),
    -- Student 4 (David Kim) - courses 1,3,4
    (4,  1, 72.00), (4,  2, 68.00),
    (4,  5, 71.00), (4,  6, 70.00),
    (4,  7, 69.00), (4,  8, 72.00),
    -- Student 5 (Elena Novak) - courses 2,4,8
    (5,  3, 67.00), (5,  4, 63.00),
    (5,  7, 66.00), (5,  8, 64.00),
    (5, 15, 68.00), (5, 16, 63.00),
    -- Student 6 (Felix Okafor) - courses 1,6,8
    (6,  1, 94.00), (6,  2, 91.00),
    (6, 11, 93.00), (6, 12, 92.00),
    (6, 15, 95.00), (6, 16, 90.00),
    -- Student 7 (Grace Liu) - courses 3,4,6
    (7,  5, 80.00), (7,  6, 77.00),
    (7,  7, 79.00), (7,  8, 78.00),
    (7, 11, 81.00), (7, 12, 77.00),
    -- Student 8 (Henry Taylor) - courses 1,2,8
    (8,  1, 87.00), (8,  2, 84.00),
    (8,  3, 88.00), (8,  4, 83.00),
    (8, 15, 86.00), (8, 16, 85.00),
    -- Student 9 (Isabella Rossi) - courses 5,6
    (9,  9, 92.00), (9, 10, 89.00),
    (9, 11, 91.00), (9, 12, 90.00),
    -- Student 10 (James Patel) - courses 5,6
    (10,  9, 74.00), (10, 10, 71.00),
    (10, 11, 73.00), (10, 12, 70.00),
    -- Student 11 (Karen Schmidt) - courses 5,6,3
    (11,  9, 70.00), (11, 10, 67.00),
    (11, 11, 69.00), (11, 12, 66.00),
    (11,  5, 70.00), (11,  6, 67.00),
    -- Student 12 (Liam O'Brien) - courses 5,6
    (12,  9, 90.00), (12, 10, 87.00),
    (12, 11, 89.00), (12, 12, 88.00),
    -- Student 13 (Maya Singh) - courses 7,1
    (13, 13, 76.00), (13, 14, 73.00),
    (13,  1, 75.00), (13,  2, 74.00),
    -- Student 14 (Nathan Weber) - courses 7,5
    (14, 13, 82.00), (14, 14, 79.00),
    (14,  9, 81.00), (14, 10, 80.00),
    -- Student 15 (Olivia Muller) - courses 7,6
    (15, 13, 78.00), (15, 14, 75.00),
    (15, 11, 77.00), (15, 12, 76.00),
    -- Student 16 (Patrick Nguyen) - courses 7,2
    (16, 13, 85.00), (16, 14, 82.00),
    (16,  3, 84.00), (16,  4, 83.00),
    -- Student 17 (Quinn Johansson) - courses 1,6
    (17,  1, 73.00), (17,  2, 70.00),
    (17, 11, 72.00), (17, 12, 71.00),
    -- Student 18 (Rachel Costa) - courses 1,6
    (18,  1, 81.00), (18,  2, 78.00),
    (18, 11, 80.00), (18, 12, 79.00),
    -- Student 19 (Samuel Andersen) - course 6 only
    (19, 11, 67.00), (19, 12, 64.00),
    -- Student 20 (Tara Blackwood) - courses 1,6
    (20,  1, 86.00), (20,  2, 83.00),
    (20, 11, 85.00), (20, 12, 84.00);

-- Final grades (22, the Canvas copy)
-- Same values as PostgreSQL enrollments.final_grade (the Osiris copy):
-- weighted per course, e.g. course 1 = 0.30*assignment + 0.70*exam.
-- Only completed (2023-2024) enrollments have one.
INSERT INTO final_grades (student_id, course_id, final_grade) VALUES
    ( 1, 1, 81.20), ( 1, 2, 82.50),
    ( 2, 1, 74.60), ( 2, 2, 74.50),
    ( 3, 2, 87.90),
    ( 4, 1, 69.20),
    ( 5, 2, 64.20),
    ( 6, 1, 91.90),
    ( 8, 1, 84.90), ( 8, 2, 84.50),
    ( 9, 5, 89.90),
    (10, 5, 71.90),
    (11, 5, 67.90),
    (12, 5, 87.90),
    (13, 7, 73.90), (13, 1, 74.30),
    (14, 7, 79.90),
    (15, 7, 75.90),
    (16, 7, 82.90),
    (17, 1, 70.90),
    (18, 1, 78.90),
    (20, 1, 83.90);
