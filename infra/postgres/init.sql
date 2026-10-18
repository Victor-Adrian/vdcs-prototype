-- Student Registry (PostgreSQL)
-- Authoritative source for students, programmes, and enrolments.
-- enrollments.course_id corresponds to MySQL courses(id), but this
-- database does not enforce that relationship.

CREATE TABLE programmes (
    id         SERIAL       PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    department VARCHAR(100) NOT NULL
);

CREATE TABLE students (
    id            SERIAL       PRIMARY KEY,
    full_name     VARCHAR(100) NOT NULL,
    date_of_birth DATE         NOT NULL,
    programme_id  INTEGER      NOT NULL REFERENCES programmes(id)
);

CREATE TABLE enrollments (
    id            SERIAL      PRIMARY KEY,
    student_id    INTEGER     NOT NULL REFERENCES students(id),
    course_id     INTEGER     NOT NULL,   -- authoritative in MySQL; no FK here
    academic_year VARCHAR(9)  NOT NULL,   -- format: 'YYYY-YYYY'
    final_grade   DECIMAL(5,2)            -- replicated in MySQL final_grades
);

-- Programmes
INSERT INTO programmes (id, name, department) VALUES
    (1, 'Computer Science',       'Computer Science'),
    (2, 'Mathematics',            'Mathematics'),
    (3, 'Electrical Engineering', 'Engineering'),
    (4, 'Business Administration','Business');

-- Students (20)
-- IDs 1-8:  Computer Science
-- IDs 9-12: Mathematics
-- IDs 13-16: Electrical Engineering
-- IDs 17-20: Business Administration
INSERT INTO students (id, full_name, date_of_birth, programme_id) VALUES
    ( 1, 'Alice Chen',       '2001-03-15', 1),
    ( 2, 'Bob Martinez',     '2001-07-22', 1),
    ( 3, 'Charlotte Williams','2000-11-08', 1),
    ( 4, 'David Kim',        '2002-01-30', 1),
    ( 5, 'Elena Novak',      '2001-05-17', 1),
    ( 6, 'Felix Okafor',     '2000-09-03', 1),
    ( 7, 'Grace Liu',        '2001-12-21', 1),
    ( 8, 'Henry Taylor',     '2002-04-11', 1),
    ( 9, 'Isabella Rossi',   '2001-06-28', 2),
    (10, 'James Patel',      '2000-08-14', 2),
    (11, 'Karen Schmidt',    '2001-02-05', 2),
    (12, 'Liam O''Brien',    '2002-10-19', 2),
    (13, 'Maya Singh',       '2001-04-07', 3),
    (14, 'Nathan Weber',     '2000-12-31', 3),
    (15, 'Olivia Muller',    '2001-09-16', 3),
    (16, 'Patrick Nguyen',   '2002-07-03', 3),
    (17, 'Quinn Johansson',  '2001-01-24', 4),
    (18, 'Rachel Costa',     '2000-06-09', 4),
    (19, 'Samuel Andersen',  '2002-03-28', 4),
    (20, 'Tara Blackwood',   '2001-11-12', 4);

-- Enrollments (48)
-- course_id values correspond to courses defined in MySQL (IDs 1-8)
-- CS students: primarily CS courses (1-4,8), some cross-enroll in Stats (6)
-- Math students: Linear Algebra (5), Stats (6), some CS
-- EE students: Circuit Theory (7), plus one other
-- Business students: Intro to Programming (1), Stats (6)
INSERT INTO enrollments (student_id, course_id, academic_year) VALUES
    -- Student 1 (Alice Chen, CS): courses 1,2,3
    (1, 1, '2023-2024'), (1, 2, '2023-2024'), (1, 3, '2024-2025'),
    -- Student 2 (Bob Martinez, CS): courses 1,2,4
    (2, 1, '2023-2024'), (2, 2, '2023-2024'), (2, 4, '2024-2025'),
    -- Student 3 (Charlotte Williams, CS): courses 2,3,8
    (3, 2, '2023-2024'), (3, 3, '2024-2025'), (3, 8, '2024-2025'),
    -- Student 4 (David Kim, CS): courses 1,3,4
    (4, 1, '2023-2024'), (4, 3, '2024-2025'), (4, 4, '2024-2025'),
    -- Student 5 (Elena Novak, CS): courses 2,4,8
    (5, 2, '2023-2024'), (5, 4, '2024-2025'), (5, 8, '2024-2025'),
    -- Student 6 (Felix Okafor, CS): courses 1,6,8
    (6, 1, '2023-2024'), (6, 6, '2024-2025'), (6, 8, '2024-2025'),
    -- Student 7 (Grace Liu, CS): courses 3,4,6
    (7, 3, '2024-2025'), (7, 4, '2024-2025'), (7, 6, '2024-2025'),
    -- Student 8 (Henry Taylor, CS): courses 1,2,8
    (8, 1, '2023-2024'), (8, 2, '2023-2024'), (8, 8, '2024-2025'),
    -- Student 9 (Isabella Rossi, Math): courses 5,6
    (9,  5, '2023-2024'), (9,  6, '2024-2025'),
    -- Student 10 (James Patel, Math): courses 5,6
    (10, 5, '2023-2024'), (10, 6, '2024-2025'),
    -- Student 11 (Karen Schmidt, Math): courses 5,6,3
    (11, 5, '2023-2024'), (11, 6, '2024-2025'), (11, 3, '2024-2025'),
    -- Student 12 (Liam O'Brien, Math): courses 5,6
    (12, 5, '2023-2024'), (12, 6, '2024-2025'),
    -- Student 13 (Maya Singh, EE): courses 7,1
    (13, 7, '2023-2024'), (13, 1, '2023-2024'),
    -- Student 14 (Nathan Weber, EE): courses 7,5
    (14, 7, '2023-2024'), (14, 5, '2024-2025'),
    -- Student 15 (Olivia Muller, EE): courses 7,6
    (15, 7, '2023-2024'), (15, 6, '2024-2025'),
    -- Student 16 (Patrick Nguyen, EE): courses 7,2
    (16, 7, '2023-2024'), (16, 2, '2024-2025'),
    -- Student 17 (Quinn Johansson, Business): courses 1,6
    (17, 1, '2023-2024'), (17, 6, '2024-2025'),
    -- Student 18 (Rachel Costa, Business): courses 1,6
    (18, 1, '2023-2024'), (18, 6, '2024-2025'),
    -- Student 19 (Samuel Andersen, Business): course 6
    (19, 6, '2024-2025'),
    -- Student 20 (Tara Blackwood, Business): courses 1,6
    (20, 1, '2023-2024'), (20, 6, '2024-2025');

-- Final grades (Osiris copy)
-- Completed (2023-2024) enrollments carry the official final grade,
-- computed with the course's assessment weights over the grades stored
-- in MySQL (e.g. course 1: 0.30*assignment + 0.70*exam). 2024-2025
-- enrollments are in progress, so final_grade stays NULL.
-- The MySQL source seeds the identical values in its final_grades table.
UPDATE enrollments SET final_grade = 81.20 WHERE student_id =  1 AND course_id = 1;
UPDATE enrollments SET final_grade = 82.50 WHERE student_id =  1 AND course_id = 2;
UPDATE enrollments SET final_grade = 74.60 WHERE student_id =  2 AND course_id = 1;
UPDATE enrollments SET final_grade = 74.50 WHERE student_id =  2 AND course_id = 2;
UPDATE enrollments SET final_grade = 87.90 WHERE student_id =  3 AND course_id = 2;
UPDATE enrollments SET final_grade = 69.20 WHERE student_id =  4 AND course_id = 1;
UPDATE enrollments SET final_grade = 64.20 WHERE student_id =  5 AND course_id = 2;
UPDATE enrollments SET final_grade = 91.90 WHERE student_id =  6 AND course_id = 1;
UPDATE enrollments SET final_grade = 84.90 WHERE student_id =  8 AND course_id = 1;
UPDATE enrollments SET final_grade = 84.50 WHERE student_id =  8 AND course_id = 2;
UPDATE enrollments SET final_grade = 89.90 WHERE student_id =  9 AND course_id = 5;
UPDATE enrollments SET final_grade = 71.90 WHERE student_id = 10 AND course_id = 5;
UPDATE enrollments SET final_grade = 67.90 WHERE student_id = 11 AND course_id = 5;
UPDATE enrollments SET final_grade = 87.90 WHERE student_id = 12 AND course_id = 5;
UPDATE enrollments SET final_grade = 73.90 WHERE student_id = 13 AND course_id = 7;
UPDATE enrollments SET final_grade = 74.30 WHERE student_id = 13 AND course_id = 1;
UPDATE enrollments SET final_grade = 79.90 WHERE student_id = 14 AND course_id = 7;
UPDATE enrollments SET final_grade = 75.90 WHERE student_id = 15 AND course_id = 7;
UPDATE enrollments SET final_grade = 82.90 WHERE student_id = 16 AND course_id = 7;
UPDATE enrollments SET final_grade = 70.90 WHERE student_id = 17 AND course_id = 1;
UPDATE enrollments SET final_grade = 78.90 WHERE student_id = 18 AND course_id = 1;
UPDATE enrollments SET final_grade = 83.90 WHERE student_id = 20 AND course_id = 1;

-- Reset sequences to max inserted id so future inserts work correctly
SELECT setval('programmes_id_seq', (SELECT MAX(id) FROM programmes));
SELECT setval('students_id_seq',   (SELECT MAX(id) FROM students));
SELECT setval('enrollments_id_seq',(SELECT MAX(id) FROM enrollments));
