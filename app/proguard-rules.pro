# kotlinx.serialization: keep generated serializers for our DTOs.
-keepclassmembers @kotlinx.serialization.Serializable class app.t3widget.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.t3widget.**$$serializer { *; }
