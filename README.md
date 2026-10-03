# HomeNurse 🏥

> A privacy-first, on-device healthcare companion for managing medical documents, medicines, care plans, and health questions.

HomeNurse is a native Android healthcare application designed to help patients and families organize and understand their medical information in one place.

The core principle of HomeNurse is **privacy by design**: sensitive medical information is processed and stored locally on the user's device wherever possible, while the backend is limited to services such as authentication and AI model distribution.

---

## ✨ Features

### 📄 Medical Document Management

- Import medical documents from the device
- Scan documents using the phone camera
- Support prescriptions, lab reports, discharge summaries, and medical reports
- Local OCR processing
- Extract structured medical information from documents
- Review extracted information before it becomes trusted
- Keep original documents alongside extracted information

### 💊 Medicine Management

- Store confirmed medicines
- Track dosage and frequency
- View medication instructions
- Associate medicines with their source documents
- Clearly distinguish confirmed information from AI-generated explanations

### 📋 Care Plan

- View confirmed care instructions
- View upcoming medicines and care tasks
- Organize follow-up instructions
- Simple timeline-based presentation
- No fabricated schedules or medical instructions

### 🤖 Ask HomeNurse

HomeNurse includes an on-device AI assistant for questions about the user's medical information.

The assistant can use confirmed information from the user's documents to provide contextual answers.

Examples:

- "What medicine do I take after dinner?"
- "What did my doctor prescribe?"
- "What does this lab result mean?"
- "What are my documented allergies?"

AI responses are designed to distinguish between:

- Information confirmed from the user's medical documents
- General health information
- Information that could not be determined from available data

The application must never silently modify a diagnosis, dosage, allergy, prescription, or doctor's instruction.

### 🔐 Privacy-First Architecture

HomeNurse is designed around local processing.

Sensitive medical information is intended to remain on the user's device, including:

- Medical documents
- OCR output
- Extracted medical facts
- Medicines
- Diagnoses
- Allergies
- Care plans
- AI conversations
- AI prompts and responses

The backend does **not** act as a medical-data processing service.

---

# 🏗️ Architecture

HomeNurse consists of two primary components:

```text
┌─────────────────────────────────────────────┐
│              HomeNurse Android              │
│                                             │
│  Documents                                  │
│      ↓                                      │
│  Image Processing                           │
│      ↓                                      │
│  Local OCR                                  │
│      ↓                                      │
│  Fact Extraction                            │
│      ↓                                      │
│  User Review & Confirmation                 │
│      ↓                                      │
│  Trusted Medical Context                    │
│      ↓                                      │
│  Local AI / Gemma                           │
│      ↓                                      │
│  Safety / Response Engine                   │
│                                             │
│  Room + Encrypted Local Storage              │
└──────────────────────┬──────────────────────┘
                       │
                       │ HTTPS
                       │
                       ▼
┌─────────────────────────────────────────────┐
│             HomeNurse Backend               │
│                                             │
│  Authentication                             │
│  Google Sign-In                             │
│  Password Reset                             │
│  Session Management                          │
│  AI Model Distribution                       │
│  Model Manifest / Metadata                   │
│                                             │
│  No medical document processing              │
│  No medical AI inference                     │
└─────────────────────────────────────────────┘
