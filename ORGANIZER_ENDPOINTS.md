# 🚀 Organizer API Endpoints Documentation

## Base URL
```
http://localhost:8080/api/organizers
```

## Authentication
Protected endpoints require JWT authentication via `Authorization: Bearer <token>` header.

---

## Endpoints Summary

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/organizers` | ❌ Public | Get all organizers |
| GET | `/organizers/:id` | ❌ Public | Get organizer by ID |
| GET | `/organizers/:id/statistics` | ❌ Public | Get organizer statistics |
| GET | `/organizers/me` | ✅ Required | Get my organizer profile |
| GET | `/organizers/me/check` | ✅ Required | Check if I'm an organizer |
| POST | `/organizers` | ✅ Required | Create new organizer |
| PATCH | `/organizers/:id` | ✅ Required | Update organizer |
| DELETE | `/organizers/:id` | ✅ Required | Delete organizer |

---

## 1. Get All Organizers

Get a list of all organizers in the system.

**Endpoint:** `GET /api/organizers`
**Auth:** Public (no authentication required)

### Response Example
```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Club Pádel Madrid",
    "description": "Club de pádel profesional en Madrid",
    "logo_url": "https://example.com/logo.png",
    "primary_color": "#FF6B00",
    "secondary_color": "#00A8E8",
    "contact_email": "info@clubpadelmadrid.com",
    "contact_phone": "+34 123 456 789",
    "instagram": "clubpadelmadrid",
    "facebook": "https://facebook.com/clubpadelmadrid",
    "created_by_uid": "user_123_uid",
    "created_at": "2025-01-01T10:00:00Z",
    "updated_at": "2025-01-01T10:00:00Z"
  }
]
```

---

## 2. Get Organizer by ID

Get details of a specific organizer.

**Endpoint:** `GET /api/organizers/:id`
**Auth:** Public (no authentication required)

### Path Parameters
- `id` (string, required) - Organizer UUID

### Response Example
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Club Pádel Madrid",
  "description": "Club de pádel profesional en Madrid",
  "logo_url": "https://example.com/logo.png",
  "primary_color": "#FF6B00",
  "secondary_color": "#00A8E8",
  "contact_email": "info@clubpadelmadrid.com",
  "contact_phone": "+34 123 456 789",
  "instagram": "clubpadelmadrid",
  "facebook": "https://facebook.com/clubpadelmadrid",
  "created_by_uid": "user_123_uid",
  "created_at": "2025-01-01T10:00:00Z",
  "updated_at": "2025-01-01T10:00:00Z"
}
```

### Error Responses

**404 Not Found**
```json
{
  "error": "Organizer not found"
}
```

**400 Bad Request**
```json
{
  "error": "Organizer ID is required"
}
```

---

## 3. Get Organizer Statistics

Get statistics for a specific organizer (tournaments, registrations, revenue).

**Endpoint:** `GET /api/organizers/:id/statistics`
**Auth:** Public (no authentication required)

### Path Parameters
- `id` (string, required) - Organizer UUID

### Response Example
```json
{
  "total_tournaments": 25,
  "active_tournaments": 8,
  "total_registrations": 450,
  "total_revenue": 135000
}
```

### Field Descriptions
- `total_tournaments`: Total number of tournaments created
- `active_tournaments`: Number of tournaments that haven't ended yet
- `total_registrations`: Total teams registered across all tournaments
- `total_revenue`: Total revenue in cents (divide by 100 for currency)

### Error Responses

**404 Not Found**
```json
{
  "error": "Statistics not found"
}
```

---

## 4. Get My Organizer Profile

Get the organizer profile for the authenticated user.

**Endpoint:** `GET /api/organizers/me`
**Auth:** ✅ Required (JWT token)

### Headers
```
Authorization: Bearer <jwt_token>
```

### Response Example
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Club Pádel Madrid",
  "description": "Club de pádel profesional en Madrid",
  "logo_url": "https://example.com/logo.png",
  "primary_color": "#FF6B00",
  "secondary_color": "#00A8E8",
  "contact_email": "info@clubpadelmadrid.com",
  "contact_phone": "+34 123 456 789",
  "instagram": "clubpadelmadrid",
  "facebook": "https://facebook.com/clubpadelmadrid",
  "created_by_uid": "user_123_uid",
  "created_at": "2025-01-01T10:00:00Z",
  "updated_at": "2025-01-01T10:00:00Z"
}
```

### Error Responses

**404 Not Found**
```json
{
  "error": "You don't have an organizer profile"
}
```

**401 Unauthorized**
```json
{
  "error": "UID missing in token"
}
```

---

## 5. Check If I'm an Organizer

Check if the authenticated user has an organizer profile.

**Endpoint:** `GET /api/organizers/me/check`
**Auth:** ✅ Required (JWT token)

### Headers
```
Authorization: Bearer <jwt_token>
```

### Response Example
```json
{
  "isOrganizer": true
}
```

### Possible Responses
- `{"isOrganizer": true}` - User has an organizer profile
- `{"isOrganizer": false}` - User does not have an organizer profile

---

## 6. Create Organizer

Create a new organizer profile for the authenticated user.

**Endpoint:** `POST /api/organizers`
**Auth:** ✅ Required (JWT token)

### Headers
```
Authorization: Bearer <jwt_token>
Content-Type: application/json
```

### Request Body
```json
{
  "name": "Club Pádel Madrid",
  "description": "Club de pádel profesional en Madrid",
  "contact_email": "info@clubpadelmadrid.com",
  "contact_phone": "+34 123 456 789",
  "primary_color": "#FF6B00",
  "secondary_color": "#00A8E8",
  "instagram": "clubpadelmadrid",
  "facebook": "https://facebook.com/clubpadelmadrid"
}
```

### Field Descriptions
| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `name` | string | ✅ Yes | - | Organization name |
| `description` | string | ❌ No | "" | Organization description |
| `contact_email` | string | ✅ Yes | - | Contact email (must be valid format) |
| `contact_phone` | string | ❌ No | "" | Contact phone number |
| `primary_color` | string | ❌ No | "#007AFF" | Primary brand color (hex format #RRGGBB) |
| `secondary_color` | string | ❌ No | "#5856D6" | Secondary brand color (hex format #RRGGBB) |
| `instagram` | string | ❌ No | null | Instagram username |
| `facebook` | string | ❌ No | null | Facebook page URL |

### Validation Rules
- ✅ `name` must not be empty
- ✅ `contact_email` must be a valid email format
- ✅ `primary_color` must be in hex format (#RRGGBB)
- ✅ `secondary_color` must be in hex format (#RRGGBB)
- ✅ User can only have **ONE** organizer profile

### Response Example (201 Created)
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Club Pádel Madrid",
  "description": "Club de pádel profesional en Madrid",
  "logo_url": null,
  "primary_color": "#FF6B00",
  "secondary_color": "#00A8E8",
  "contact_email": "info@clubpadelmadrid.com",
  "contact_phone": "+34 123 456 789",
  "instagram": "clubpadelmadrid",
  "facebook": "https://facebook.com/clubpadelmadrid",
  "created_by_uid": "user_123_uid",
  "created_at": "2025-01-01T10:00:00Z",
  "updated_at": "2025-01-01T10:00:00Z"
}
```

### Error Responses

**409 Conflict** (User already has an organizer)
```json
{
  "error": "User already has an organizer profile"
}
```

**400 Bad Request** (Invalid email or color format)
```json
{
  "error": "Invalid email format"
}
```
```json
{
  "error": "Invalid primary color format. Must be #RRGGBB"
}
```

**400 Bad Request** (Invalid request body)
```json
{
  "error": "Invalid request body"
}
```

---

## 7. Update Organizer

Update an existing organizer profile.

**Endpoint:** `PATCH /api/organizers/:id`
**Auth:** ✅ Required (JWT token, must be the owner)

### Path Parameters
- `id` (string, required) - Organizer UUID

### Headers
```
Authorization: Bearer <jwt_token>
Content-Type: application/json
```

### Request Body
All fields are optional. Only include fields you want to update.

```json
{
  "name": "New Club Name",
  "description": "Updated description",
  "contact_email": "newemail@example.com",
  "contact_phone": "+34 987 654 321",
  "primary_color": "#00FF00",
  "secondary_color": "#0000FF",
  "logo_url": "https://example.com/new-logo.png",
  "instagram": "newusername",
  "facebook": "https://facebook.com/newpage"
}
```

### Field Descriptions
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | ❌ No | Organization name |
| `description` | string | ❌ No | Organization description |
| `contact_email` | string | ❌ No | Contact email (must be valid format) |
| `contact_phone` | string | ❌ No | Contact phone number |
| `primary_color` | string | ❌ No | Primary brand color (hex format #RRGGBB) |
| `secondary_color` | string | ❌ No | Secondary brand color (hex format #RRGGBB) |
| `logo_url` | string | ❌ No | Logo URL |
| `instagram` | string | ❌ No | Instagram username |
| `facebook` | string | ❌ No | Facebook page URL |

### Validation Rules
- ✅ If `contact_email` provided, must be valid format
- ✅ If `primary_color` provided, must be hex format (#RRGGBB)
- ✅ If `secondary_color` provided, must be hex format (#RRGGBB)
- ✅ Only the **owner** (creator) can update the organizer

### Response Example (200 OK)
```json
{
  "success": true
}
```

### Error Responses

**403 Forbidden** (Not the owner)
```json
{
  "error": "Only the creator can update this organizer"
}
```

**404 Not Found** (Organizer doesn't exist)
```json
{
  "error": "Organizer not found"
}
```

**400 Bad Request** (Invalid data)
```json
{
  "error": "Invalid email format"
}
```
```json
{
  "error": "Invalid primary color format. Must be #RRGGBB"
}
```

---

## 8. Delete Organizer

Delete an organizer profile.

**Endpoint:** `DELETE /api/organizers/:id`
**Auth:** ✅ Required (JWT token, must be the owner)

### Path Parameters
- `id` (string, required) - Organizer UUID

### Headers
```
Authorization: Bearer <jwt_token>
```

### Business Rules
- ✅ Only the **owner** (creator) can delete the organizer
- ⚠️ This will set all associated tournaments' `organizer_id` to NULL (orphan tournaments)
- ⚠️ This will CASCADE delete all associated rankings

### Response Example (200 OK)
```json
{
  "success": true
}
```

### Error Responses

**403 Forbidden** (Not the owner)
```json
{
  "error": "Only the creator can delete this organizer"
}
```

**404 Not Found** (Organizer doesn't exist)
```json
{
  "error": "Organizer not found"
}
```

**500 Internal Server Error**
```json
{
  "error": "Failed to delete organizer"
}
```

---

## Testing with cURL

### Get all organizers
```bash
curl http://localhost:8080/api/organizers
```

### Get organizer by ID
```bash
curl http://localhost:8080/api/organizers/550e8400-e29b-41d4-a716-446655440000
```

### Get organizer statistics
```bash
curl http://localhost:8080/api/organizers/550e8400-e29b-41d4-a716-446655440000/statistics
```

### Get my organizer (authenticated)
```bash
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
     http://localhost:8080/api/organizers/me
```

### Check if I'm an organizer
```bash
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
     http://localhost:8080/api/organizers/me/check
```

### Create organizer
```bash
curl -X POST http://localhost:8080/api/organizers \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Club Pádel Madrid",
    "description": "Club de pádel profesional",
    "contact_email": "info@clubpadel.com",
    "contact_phone": "+34 123456789",
    "primary_color": "#FF6B00",
    "secondary_color": "#00A8E8",
    "instagram": "clubpadel",
    "facebook": "https://facebook.com/clubpadel"
  }'
```

### Update organizer
```bash
curl -X PATCH http://localhost:8080/api/organizers/550e8400-e29b-41d4-a716-446655440000 \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "New Club Name",
    "primary_color": "#00FF00"
  }'
```

### Delete organizer
```bash
curl -X DELETE http://localhost:8080/api/organizers/550e8400-e29b-41d4-a716-446655440000 \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

---

## Integration with iOS App

Once the backend is ready, update the iOS app to use these endpoints:

### 1. Update Models

Add `organizerId` to `Tournament` and `Ranking` models:

```swift
struct Tournament: Codable {
    // ... existing fields ...
    let organizerId: String?

    enum CodingKeys: String, CodingKey {
        // ... existing cases ...
        case organizerId = "organizer_id"
    }
}
```

### 2. Replace Mock Data with API Calls

In `OrganizerRegistrationViewModel`:
```swift
// BEFORE (Mock):
let organizer = OrganizerMockData.shared.createOrganizer(name, email)

// AFTER (API):
let endpoint = CreateOrganizerEndpoint(request: request)
let organizer = try await APIClient().request(endpoint, as: OrganizerResponse.self)
```

### 3. Implement Endpoints

The endpoints are already defined in `OrganizerEndpoints.swift`. Just replace the mock logic with actual API calls.

---

## Database Schema

See `SUPABASE_ORGANIZERS_SCHEMA.md` for complete database documentation.

---

## Status Codes Reference

| Code | Meaning | When Used |
|------|---------|-----------|
| 200 | OK | Successful GET, PATCH, DELETE |
| 201 | Created | Successful POST (organizer created) |
| 400 | Bad Request | Invalid request body or parameters |
| 401 | Unauthorized | Missing or invalid JWT token |
| 403 | Forbidden | User doesn't have permission |
| 404 | Not Found | Resource doesn't exist |
| 409 | Conflict | User already has organizer |
| 500 | Internal Server Error | Server-side error |

---

## Next Steps

1. ✅ **Database is ready** - Migrations applied
2. ✅ **Backend is ready** - Endpoints implemented
3. ⏳ **iOS app migration** - Replace mock data with API calls
4. ⏳ **Add filtering** - Update tournament/ranking endpoints to support `?organizer_id=` filter

---

**Happy coding!** 🚀
