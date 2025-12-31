package repositories.cloudinary

import com.cloudinary.Cloudinary
import com.cloudinary.utils.ObjectUtils

class CloudinaryRepositoryImpl(
    private val cloudinary: Cloudinary
) : CloudinaryRepository {

    override fun uploadProfilePhoto(uid: String, bytes: ByteArray): String {
        val uploadResult = cloudinary.uploader().upload(
            bytes,
            ObjectUtils.asMap(
                "folder", "profilePhotos",
                "public_id", uid,
                "overwrite", true,
                "resource_type", "image"
            )
        )

        return uploadResult["secure_url"] as String
    }
}
