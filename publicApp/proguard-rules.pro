# R8 oculta las clases Kotlin y actualiza también sus nombres en el manifiesto.
-repackageclasses 'x'
-allowaccessmodification
-adaptclassstrings
-renamesourcefileattribute SourceFile

# Un único puente JNI deliberadamente opaco conserva su nombre para que el
# cargador nativo pueda resolverlo. El resto de clases se ofusca normalmente.
-keep,allowoptimization class com.freezy.publicapp.N {
    native <methods>;
}

# El motor llama este método desde JNI; se conserva el miembro sin ofuscar su nombre.
-keepclassmembers class * extends android.net.VpnService {
    public boolean protectSocket(int);
    boolean protectSocket(int);
}
-keep class com.freezy.AntigravityFirewall {
    public boolean protectSocket(int);
    *;
}
-keep class com.freezy.AntigravityFirewall$* {
    *;
}
