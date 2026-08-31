package com.tomasthrawat.wifigamereceiver.adb

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

class TvAdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val keyDir = File(context.filesDir, "adb_keys").apply { mkdirs() }
    private val privateKeyFile = File(keyDir, "adb_key.pk8")
    private val certificateFile = File(keyDir, "adb_cert.der")

    private val loadedPrivateKey: PrivateKey
    private val loadedCertificate: Certificate

    init {
        setApi(android.os.Build.VERSION.SDK_INT)

        if (privateKeyFile.exists() && certificateFile.exists()) {
            val keyFactory = KeyFactory.getInstance("RSA")
            loadedPrivateKey = keyFactory.generatePrivate(
                PKCS8EncodedKeySpec(privateKeyFile.readBytes())
            )
            loadedCertificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(certificateFile.inputStream())
        } else {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048, SecureRandom())
            val keyPair = keyPairGenerator.generateKeyPair()

            val subject = X500Name("CN=WifiGameReceiver")
            val now = Date()
            val expiry = Date(now.time + 20L * 365 * 24 * 60 * 60 * 1000)
            val serial = BigInteger(64, SecureRandom())

            val certBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
                subject, serial, now, expiry, subject, keyPair.public
            )
            val signer = JcaContentSignerBuilder("SHA512withRSA").build(keyPair.private)
            val holder = certBuilder.build(signer)
            val certificate = JcaX509CertificateConverter().getCertificate(holder)

            privateKeyFile.writeBytes(keyPair.private.encoded)
            certificateFile.writeBytes(certificate.encoded)

            loadedPrivateKey = keyPair.private
            loadedCertificate = certificate
        }
    }

    override fun getPrivateKey(): PrivateKey = loadedPrivateKey

    override fun getCertificate(): Certificate = loadedCertificate

    override fun getDeviceName(): String = "WifiGameReceiver"

    companion object {
        @Volatile
        private var instance: TvAdbConnectionManager? = null

        fun getInstance(context: Context): TvAdbConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: TvAdbConnectionManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
