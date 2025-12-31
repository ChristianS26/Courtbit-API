package repositories.cloudinary

interface CloudinaryRepository {
    fun uploadProfilePhoto(uid: String, bytes: ByteArray): String
}
