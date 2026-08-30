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

# El motor llama este método desde JNI; se conserva el miembro, no la clase.
-keepclassmembers,allowoptimization,allowobfuscation class * extends android.net.VpnService {
    boolean protectSocket(int);
}
