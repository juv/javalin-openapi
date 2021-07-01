package com.dzikoysk.openapi.processor

import com.dzikoysk.openapi.annotations.ContentType.AUTODETECT
import com.dzikoysk.openapi.annotations.NULL_CLASS
import com.dzikoysk.openapi.annotations.NULL_STRING
import com.dzikoysk.openapi.annotations.OpenApiIgnore
import com.dzikoysk.openapi.annotations.OpenApiName
import com.dzikoysk.openapi.processor.annotations.OpenApiContentInstance
import com.dzikoysk.openapi.processor.annotations.OpenApiInstance
import com.dzikoysk.openapi.processor.annotations.OpenApiParamInstance
import com.dzikoysk.openapi.processor.annotations.OpenApiParamInstance.In
import com.dzikoysk.openapi.processor.annotations.OpenApiParamInstance.In.COOKIE
import com.dzikoysk.openapi.processor.annotations.OpenApiParamInstance.In.HEADER
import com.dzikoysk.openapi.processor.annotations.OpenApiParamInstance.In.PATH
import com.dzikoysk.openapi.processor.annotations.OpenApiParamInstance.In.QUERY
import com.dzikoysk.openapi.processor.annotations.OpenApiPropertyTypeInstance
import com.dzikoysk.openapi.processor.utils.JsonUtils
import com.dzikoysk.openapi.processor.utils.TypesUtils
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import javax.lang.model.element.ElementKind.METHOD
import javax.lang.model.element.ExecutableElement
import javax.lang.model.type.TypeMirror

internal class OpenApiGenerator {

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    private val componentReferences: MutableSet<TypeMirror> = mutableSetOf()

    /**
     * Based on https://swagger.io/specification/
     *
     * @param annotations annotation instances to map
     * @return OpenApi JSON response
     */
    fun generate(annotations: Collection<OpenApiInstance>): String {
        val openApi = JsonObject()
        openApi.addProperty("openapi", "3.0.3")

        // somehow fill info { description, version } properties
        val info = JsonObject()
        info.addProperty("title", "{openapi.title}")
        info.addProperty("description", "{openapi.description}")
        info.addProperty("version", "{openapi.version}")
        openApi.add("info", info)

        // fill paths
        val paths = JsonObject()
        openApi.add("paths", paths)

        for (routeAnnotation in annotations) {
            if (routeAnnotation.ignore()) {
                continue
            }

            val path = JsonUtils.computeIfAbsent(paths, routeAnnotation.path()) { JsonObject() }

            // https://swagger.io/specification/#paths-object
            for (method in routeAnnotation.methods()) {
                val operation = JsonObject()

                // General
                operation.add("tags", JsonUtils.toArray(routeAnnotation.tags()))
                addString(operation, "summary", routeAnnotation.summary())
                addString(operation, "description", routeAnnotation.description())

                // ExternalDocs
                // ~ https://swagger.io/specification/#external-documentation-object
                // operation.addProperty("externalDocs", ); UNSUPPORTED

                // OperationId
                addString(operation, "operationId", routeAnnotation.operationId())

                // Parameters
                // ~ https://swagger.io/specification/#parameter-object
                val parameters = JsonArray()

                for (queryParameterAnnotation in routeAnnotation.queryParams()) {
                    parameters.add(fromParameter(QUERY, queryParameterAnnotation))
                }

                for (headerParameterAnnotation in routeAnnotation.headers()) {
                    parameters.add(fromParameter(HEADER, headerParameterAnnotation))
                }

                for (pathParameterAnnotation in routeAnnotation.pathParams()) {
                    parameters.add(fromParameter(PATH, pathParameterAnnotation))
                }

                for (cookieParameterAnnotation in routeAnnotation.cookies()) {
                    parameters.add(fromParameter(COOKIE, cookieParameterAnnotation))
                }

                operation.add("parameters", parameters)

                // RequestBody
                // ~ https://swagger.io/specification/#request-body-object
                val requestBodyAnnotation = routeAnnotation.requestBody()
                val requestBody = JsonObject()
                addString(requestBody, "description", requestBodyAnnotation.description())
                addContent(requestBody, requestBodyAnnotation.content())
                requestBody.addProperty("required", requestBodyAnnotation.required())
                operation.add("requestBody", requestBody)

                // Responses
                // ~ https://swagger.io/specification/#responses-object
                val responses = JsonObject()

                for (responseAnnotation in routeAnnotation.responses()) {
                    val response = JsonObject()
                    addString(response, "description", responseAnnotation.description())
                    addContent(response, responseAnnotation.content())
                    responses.add(responseAnnotation.status(), response)
                }

                operation.add("responses", responses)

                // Callbacks
                // ~ https://swagger.io/specification/#callback-object
                // operation.addProperty("callbacks, ); UNSUPPORTED

                // Deprecated
                operation.addProperty("deprecated", routeAnnotation.deprecated())

                // Security
                // ~ https://swagger.io/specification/#security-requirement-object
                val security = JsonArray()

                for (securityAnnotation in routeAnnotation.security()) {
                    val securityEntry = JsonObject()
                    val scopes = JsonArray()

                    for (scopeAnnotation in securityAnnotation.scopes()) {
                        scopes.add(scopeAnnotation)
                    }

                    securityEntry.add(securityAnnotation.name(), scopes)
                    security.add(securityEntry)
                }

                operation.add("security", security)

                // servers
                path.add(method.name.toLowerCase(), operation)
            }
        }

        val components = JsonObject()
        val schemas = JsonObject()
        val generatedComponents: MutableSet<TypeMirror> = mutableSetOf()

        while (generatedComponents.size < componentReferences.size) {
            for (componentReference in componentReferences.toMutableList()) {
                if (generatedComponents.contains(componentReference)) {
                    continue
                }

                val type = TypesUtils.getType(componentReference)
                val schema = JsonObject()
                val properties = JsonObject()

                for (property in type.element.enclosedElements) {
                    if (property is ExecutableElement && property.kind == METHOD) {
                        if (property.getAnnotation(OpenApiIgnore::class.java) != null) {
                            continue
                        }

                        val simpleName = property.simpleName.toString()
                        val customName = property.getAnnotation(OpenApiName::class.java)

                        val name = when {
                            customName != null -> customName.value
                            simpleName.startsWith("get") -> simpleName.replaceFirst("get", "").decapitalize()
                            simpleName.startsWith("is") -> simpleName.replaceFirst("is", "").decapitalize()
                            else -> continue
                        }

                        val propertyType = property.annotationMirrors
                            .firstOrNull { it.annotationType.asElement().simpleName.contentEquals("OpenApiPropertyType") }
                            ?.let { OpenApiPropertyTypeInstance(it).definedBy() }
                            ?: property.returnType

                        val propertyEntry = JsonObject()
                        addSchema(propertyEntry, propertyType, false)
                        properties.add(name, propertyEntry)
                    }
                }

                schema.addProperty("type", "object")
                schema.add("properties", properties)
                schemas.add(type.getSimpleName(), schema)
                generatedComponents.add(componentReference)
            }
        }

        components.add("schemas", schemas)
        openApi.add("components", components)

        return gson.toJson(openApi)
    }

    // Parameter
    // https://swagger.io/specification/#parameter-object
    private fun fromParameter(`in`: In, parameterInstance: OpenApiParamInstance?): JsonObject {
        val parameter = JsonObject()
        addString(parameter, "name", parameterInstance!!.name())
        addString(parameter, "in", `in`.name.toLowerCase())
        addString(parameter, "description", parameterInstance.description())
        parameter.addProperty("required", parameterInstance.required())
        parameter.addProperty("deprecated", parameterInstance.deprecated())
        parameter.addProperty("allowEmptyValue", parameterInstance.allowEmptyValue())
        val schema = JsonObject()
        addSchema(schema, OpenApiAnnotationProcessor.elements.getTypeElement(String::class.java.name).asType(), false)
        parameter.add("schema", schema)
        return parameter
    }

    private fun addContent(parent: JsonObject, annotations: List<OpenApiContentInstance>) {
        val requestBodyContent = JsonObject()

        for (contentAnnotation in annotations) {
            if (contentAnnotation.from().toString() != NULL_CLASS::class.java.name) {
                addMediaType(requestBodyContent, contentAnnotation.type(), contentAnnotation.from(), contentAnnotation.isArray())
            }
        }

        parent.add("content", requestBodyContent)
    }

    private fun addSchema(schema: JsonObject, typeMirror: TypeMirror, isArray: Boolean) {
        val type = TypesUtils.getType(typeMirror)

        if (isArray || type.isArray()) {
            schema.addProperty("type", "array")
            val items = JsonObject()
            addType(items, typeMirror)
            schema.add("items", items)
        }
        else {
            addType(schema, typeMirror)
        }
    }

    private fun addType(parent: JsonObject, typeMirror: TypeMirror) {
        val type = TypesUtils.getType(typeMirror)
        val nonRefType = TypesUtils.NON_REF_TYPES[type.getSimpleName()]

        if (nonRefType == null) {
            componentReferences.add(typeMirror)
            parent.addProperty("\$ref", "#/components/schemas/${type.getSimpleName()}")
            return
        }

        parent.addProperty("type", nonRefType.type)
        parent.addProperty("format", nonRefType.format)
    }

    private fun addString(parent: JsonObject, key: String, value: String?) {
        if (NULL_STRING != value) {
            parent.addProperty(key, value)
        }
    }

    private fun addMediaType(parent: JsonObject, mimeType: String, type: TypeMirror, isArray: Boolean) {
        var detectedContentType = mimeType

        if (AUTODETECT == detectedContentType) {
            detectedContentType = TypesUtils.detectContentType(type)
        }

        val mediaType = JsonObject()
        val schema = JsonObject()
        addSchema(schema, type, isArray)
        mediaType.add("schema", schema)
        parent.add(detectedContentType, mediaType)
    }

}