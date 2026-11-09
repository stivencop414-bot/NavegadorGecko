-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes AnnotationDefault
-dontwarn java.beans.**

# ML Kit / Firebase crean estos registradores por reflexión al iniciar el
# proceso (MlKitInitProvider). Sin su constructor, Nexo se cerraba al abrirse.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
