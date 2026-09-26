# Room entities and DAOs are reflected over at runtime.
-keep class io.launcher.home.models.** { *; }
-keep interface io.launcher.home.interfaces.**Dao { *; }

# Widget providers and the device-admin receiver are named in the manifest and instantiated by
# the system; R8 cannot see those references.
-keep class io.launcher.home.widgets.** { *; }
-keep class io.launcher.home.receivers.** { *; }

# The custom views in the launcher layouts are inflated by name.
-keep class io.launcher.home.views.** { *; }
-keep class io.launcher.home.fragments.** { *; }

# The host implements these; keep the shapes it compiles against.
-keep interface io.launcher.home.api.** { *; }
