package services.cloudinary

import repositories.cloudinary.CloudinaryRepository

class CloudinaryService(
    private val repository: CloudinaryRepository
) {
    fun uploadProfilePhoto(uid: String, bytes: ByteArray): String {
        return repository.uploadProfilePhoto(uid, bytes)
    }
}