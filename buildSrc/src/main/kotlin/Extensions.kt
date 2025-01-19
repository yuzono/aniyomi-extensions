import groovy.lang.MissingPropertyException
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.extra

var ExtensionAware.baseVersionCode: Int
    get() = extra.get("baseVersionCode") as Int + baseKmkVersionCode
    set(value) = extra.set("baseVersionCode", value)

var ExtensionAware.baseKmkVersionCode: Int
    get() {
        return try {
            extra.get("baseKmkVersionCode") as Int
        } catch (e: MissingPropertyException) {
            0
        }
    }
    set(value) = extra.set("baseKmkVersionCode", value)
