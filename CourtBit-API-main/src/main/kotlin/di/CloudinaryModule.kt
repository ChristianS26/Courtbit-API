package di

import com.cloudinary.Cloudinary
import org.koin.dsl.module
import repositories.cloudinary.CloudinaryRepository
import repositories.cloudinary.CloudinaryRepositoryImpl
import services.cloudinary.CloudinaryService

val CloudinaryModule = module {

    single {
        Cloudinary(System.getenv("CLOUDINARY_URL"))
    }

    single<CloudinaryRepository> {
        CloudinaryRepositoryImpl(get())
    }

    single {
        CloudinaryService(get())
    }
}
