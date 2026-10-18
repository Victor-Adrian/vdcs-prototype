// Student Activities (MongoDB)
// Authoritative source for extracurricular records.
// Student IDs correspond to PostgreSQL students(id).

db = db.getSiblingDB('activities');

db.student_activities.insertMany([
  // Computer Science students (1-8)
  {
    student_id: 1,
    clubs: [
      { name: "Coding Society",  role: "Member",         joined: "2022-09" },
      { name: "Chess Club",      role: "Member",         joined: "2023-02" }
    ],
    projects: [
      { title: "Compiler Design Experiment", year: 2024, status: "completed" }
    ]
  },
  {
    student_id: 2,
    clubs: [
      { name: "Robotics Club",   role: "Member",         joined: "2023-09" }
    ],
    projects: []
  },
  {
    student_id: 3,
    clubs: [
      { name: "Coding Society",  role: "Vice-President", joined: "2022-09" },
      { name: "Debate Society",  role: "Member",         joined: "2023-10" }
    ],
    projects: [
      { title: "ML Text Classifier", year: 2024, status: "completed" }
    ]
  },
  {
    student_id: 4,
    clubs: [],
    projects: [
      { title: "Web Scraper Tool", year: 2023, status: "completed" }
    ]
  },
  {
    student_id: 5,
    clubs: [
      { name: "Running Club",    role: "Member",         joined: "2022-09" }
    ],
    projects: []
  },
  {
    student_id: 6,
    clubs: [
      { name: "Coding Society",  role: "President",      joined: "2021-09" },
      { name: "Photography Club",role: "Member",         joined: "2022-03" }
    ],
    projects: [
      { title: "Semantic Web App Prototype", year: 2024, status: "completed" }
    ]
  },
  {
    student_id: 7,
    clubs: [
      { name: "Chess Club",      role: "President",      joined: "2022-09" },
      { name: "Math Olympiad",   role: "Member",         joined: "2023-01" }
    ],
    projects: []
  },
  {
    student_id: 8,
    clubs: [
      { name: "Robotics Club",   role: "Vice-President", joined: "2022-09" },
      { name: "Film Club",       role: "Member",         joined: "2023-11" }
    ],
    projects: [
      { title: "Autonomous Robot Navigation", year: 2024, status: "in-progress" }
    ]
  },

  // Mathematics students (9-12)
  {
    student_id: 9,
    clubs: [
      { name: "Math Olympiad",   role: "Vice-President", joined: "2022-09" },
      { name: "Coding Society",  role: "Member",         joined: "2023-09" }
    ],
    projects: []
  },
  {
    student_id: 10,
    clubs: [
      { name: "Chess Club",      role: "Member",         joined: "2023-03" }
    ],
    projects: []
  },
  {
    student_id: 11,
    clubs: [
      { name: "Math Olympiad",   role: "President",      joined: "2021-09" }
    ],
    projects: [
      { title: "Number Theory Research Survey", year: 2023, status: "completed" }
    ]
  },
  {
    student_id: 12,
    clubs: [
      { name: "Debate Society",  role: "Member",         joined: "2022-10" },
      { name: "Running Club",    role: "Member",         joined: "2023-04" }
    ],
    projects: []
  },

  // Electrical Engineering students (13-16)
  {
    student_id: 13,
    clubs: [
      { name: "Robotics Club",          role: "Vice-President", joined: "2021-09" },
      { name: "Sustainability Initiative", role: "Member",      joined: "2022-11" }
    ],
    projects: []
  },
  {
    student_id: 14,
    clubs: [
      { name: "Robotics Club",   role: "Member",         joined: "2023-09" },
      { name: "Music Ensemble",  role: "Member",         joined: "2022-09" }
    ],
    projects: []
  },
  {
    student_id: 15,
    clubs: [
      { name: "Sustainability Initiative", role: "President", joined: "2022-09" }
    ],
    projects: [
      { title: "Solar Panel Efficiency Analysis", year: 2024, status: "in-progress" }
    ]
  },
  {
    student_id: 16,
    clubs: [
      { name: "Photography Club",role: "Vice-President", joined: "2022-09" },
      { name: "Film Club",       role: "Member",         joined: "2023-01" }
    ],
    projects: []
  },

  // Business Administration students (17-20)
  {
    student_id: 17,
    clubs: [
      { name: "Debate Society",  role: "President",      joined: "2021-09" },
      { name: "Running Club",    role: "Member",         joined: "2022-04" }
    ],
    projects: []
  },
  {
    student_id: 18,
    clubs: [
      { name: "Music Ensemble",  role: "Vice-President", joined: "2022-09" }
    ],
    projects: []
  },
  {
    student_id: 19,
    clubs: [
      { name: "Film Club",       role: "Member",         joined: "2022-11" },
      { name: "Photography Club",role: "Member",         joined: "2023-06" }
    ],
    projects: []
  },
  {
    student_id: 20,
    clubs: [
      { name: "Running Club",    role: "President",      joined: "2021-09" },
      { name: "Sustainability Initiative", role: "Member", joined: "2022-09" }
    ],
    projects: []
  }
]);

print("Seeded " + db.student_activities.countDocuments() + " student activity documents.");
