package light.rift.fmt.ssh

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import light.rift.fmt.AbstractBean
import light.rift.fmt.KryoConverters

class SSHBean : AbstractBean() {

    var username: String = "root"
    var authType: Int = AUTH_TYPE_PASSWORD
    var password: String = ""
    var privateKey: String = ""
    var privateKeyPassphrase: String = ""
    var publicKey: String = ""

    override fun initializeDefaultValues() {
        if (serverPort == 0) serverPort = 22

        super.initializeDefaultValues()

        if (username == null) username = "root"
        if (authType == null) authType = AUTH_TYPE_PASSWORD
        if (password == null) password = ""
        if (privateKey == null) privateKey = ""
        if (privateKeyPassphrase == null) privateKeyPassphrase = ""
        if (publicKey == null) publicKey = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeString(username)
        output.writeInt(authType!!)
        when (authType) {
            AUTH_TYPE_NONE -> {
            }
            AUTH_TYPE_PASSWORD -> {
                output.writeString(password)
            }
            AUTH_TYPE_PRIVATE_KEY -> {
                output.writeString(privateKey)
                output.writeString(privateKeyPassphrase)
            }
        }
        output.writeString(publicKey)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        username = input.readString()
        authType = input.readInt()
        when (authType) {
            AUTH_TYPE_NONE -> {
            }
            AUTH_TYPE_PASSWORD -> {
                password = input.readString()
            }
            AUTH_TYPE_PRIVATE_KEY -> {
                privateKey = input.readString()
                privateKeyPassphrase = input.readString()
            }
        }
        publicKey = input.readString()
    }

    override fun clone(): SSHBean {
        return KryoConverters.deserialize(SSHBean(), KryoConverters.serialize(this))
    }

    companion object {
        const val AUTH_TYPE_NONE = 0
        const val AUTH_TYPE_PASSWORD = 1
        const val AUTH_TYPE_PRIVATE_KEY = 2

        @JvmField
        val CREATOR = object : CREATOR<SSHBean>() {
            override fun newInstance(): SSHBean {
                return SSHBean()
            }

            override fun newArray(size: Int): Array<SSHBean?> {
                return arrayOfNulls(size)
            }
        }
    }
}
