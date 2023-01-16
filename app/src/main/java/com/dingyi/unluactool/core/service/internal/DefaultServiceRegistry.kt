package com.dingyi.unluactool.core.service.internal

import com.dingyi.unluactool.core.service.*
import com.dingyi.unluactool.core.service.ContainsServices
import com.dingyi.unluactool.core.service.internal.TypeStringFormatter.format
import com.dingyi.unluactool.core.util.JsonConfigReader
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.util.*


open class DefaultServiceRegistry(displayName: String?, vararg parents: ServiceRegistry) :
    ServiceRegistry, ContainsServices {

    companion object {

        private val NO_PARENTS = arrayOf<ServiceRegistry>()

        private fun setupParentServices(parents: Array<out ServiceRegistry>): ServiceProvider {
            val parentServices: ServiceProvider
            if (parents.size == 1) {
                parentServices = toParentServices(parents[0])
            } else {
                val parentServiceProviders = arrayOfNulls<ServiceProvider>(parents.size)
                for (i in parents.indices) {
                    parentServiceProviders[i] = toParentServices(parents[i])
                }
                parentServices = CompositeServiceProvider(*parentServiceProviders.requireNoNulls())
            }
            return parentServices
        }

        private fun toParentServices(serviceRegistry: ServiceRegistry): ServiceProvider {
            if (serviceRegistry is ContainsServices) {
                return ParentServices((serviceRegistry as ContainsServices).asProvider())
            }
            throw IllegalArgumentException(
                java.lang.String.format(
                    "Service registry %s cannot be used as a parent for another service registry.",
                    serviceRegistry
                )
            )
        }
    }

    private var allServices: ServiceProvider
    private val parentServices: ServiceProvider
    private val ownServices: DefaultServiceRegistry.OwnServices
    private val displayName: String?


    init {
        this.displayName = displayName
        this.ownServices = OwnServices()
        if (parents.isEmpty()) {
            this.parentServices = ServiceProvider.EmptyServiceProvider
            this.allServices = ownServices
        } else {
            this.parentServices = setupParentServices(parents)
            this.allServices = CompositeServiceProvider(ownServices, parentServices)
        }

        findServicesForConfig()
        findProviderMethods(this)
    }


    private fun findServicesForConfig() {
        val jsonObject = JsonConfigReader.readConfig("service-global.json").asJsonObject

        jsonObject.keySet().forEach {
            val interfaceClassName = it
            val implementClassName = jsonObject[it].asString
            ownServices.add(ConstructorService(this, Class.forName(implementClassName),Class.forName(interfaceClassName)))
        }
    }

    private fun findProviderMethods(target: Any) {

        //1.getAllMethodsAndFilter

        val targetMethods = target::class.java.declaredMethods
            .filter { it.name.startsWith("create") }

        if (targetMethods.isEmpty())
            return

        //2. addService


        targetMethods.forEach {
            it.isAccessible = true
            ownServices.add(FactoryService(this, it.returnType, it, target))
        }


    }

    constructor(displayName: String?) : this(displayName, *NO_PARENTS)

    constructor(vararg parents: ServiceRegistry) : this(null, *parents)

    private class CompositeServiceProvider(vararg argServiceProviders: ServiceProvider) :
        ServiceProvider {
        private val serviceProviders: Array<out ServiceProvider>

        init {
            this.serviceProviders = argServiceProviders
        }

        override fun getService(serviceType: Class<*>): Service? {
            for (serviceProvider in serviceProviders) {
                val service = serviceProvider.getService(serviceType)
                if (service != null) {
                    return service
                }
            }
            return null
        }

        override fun getAll(serviceType: Class<*>): Iterator<Service> {
            return serviceProviders.flatMap { it.getAll(serviceType).asSequence() }.iterator()
        }

    }

    private inner class OwnServices : ServiceProvider {
        private val providersByType: MutableMap<Class<*>, MutableList<ServiceProvider>> =
            HashMap(16, 0.5f)
        private val services = ArrayList<SingletonService>()

        init {
            providersByType[ServiceRegistry::class.java] =
                Collections.singletonList(ThisAsService());
        }


        override fun getService(serviceType: Class<*>): Service? {
            val serviceProviders = getProviders(unwrap(serviceType))
            if (serviceProviders.isEmpty()) {
                return null
            }
            if (serviceProviders.size == 1) {
                return serviceProviders[0].getService(serviceType)
            }
            val services = ArrayList<Service>(serviceProviders.size)
            for (serviceProvider in serviceProviders) {
                val service = serviceProvider.getService(serviceType)
                if (service != null) {
                    services.add(service)
                }
            }
            if (services.isEmpty()) {
                return null
            }
            if (services.size == 1) {
                return services[0]
            }
            throw RuntimeException("Multiple services of type %s".format(serviceType))
        }

        override fun getAll(serviceType: Class<*>): Iterator<Service> {
            return services.filter { it.serviceType.isAssignableFrom(serviceType) }.iterator()
        }


        private fun getProviders(type: Class<*>): List<ServiceProvider> {
            val providers = providersByType[type]
            return providers ?: Collections.emptyList()
        }


        fun add(serviceProvider: SingletonService) {
            services.add(serviceProvider)
            putServiceType(serviceProvider.serviceType, serviceProvider)
        }

        private fun putServiceType(type: Class<*>, serviceProvider: ServiceProvider) {
            var serviceProviders = providersByType[type]
            if (serviceProviders == null) {
                serviceProviders = ArrayList(2)
                providersByType[type] = serviceProviders
            }
            serviceProviders.add(serviceProvider)
        }

    }

    private inner class ThisAsService : ServiceProvider, Service {
        override fun getService(serviceType: Class<*>): Service? {
            return if (serviceType == ServiceRegistry::class.java) {
                this
            } else null
        }

        override fun getAll(serviceType: Class<*>): Iterator<Service> {
            return arrayOf(this).filter { serviceType == ServiceRegistry::class.java }.iterator()
        }


        override fun getDisplayName(): String {
            return "ServiceRegistry " + this@DefaultServiceRegistry.getDisplayName()
        }

        override fun get(): Any {
            return this@DefaultServiceRegistry
        }


    }



    private class ConstructorService constructor(
        owner: DefaultServiceRegistry,
        serviceType: Class<*>,
        interfaceType: Class<*>
    ) : SingletonService(owner, interfaceType) {
        private val constructor: Constructor<*>

        init {
            if (serviceType.isInterface) {
                error("Cannot register an interface for construction.")
            }
            val match: Constructor<*> = InjectUtil.selectConstructor(serviceType)
            if (InjectUtil.isPackagePrivate(match.modifiers) || Modifier.isPrivate(match.modifiers)) {
                match.isAccessible = true
            }
            constructor = match
        }

        private val parameterTypes = constructor.genericParameterTypes


        protected fun invokeMethod(params: Array<Any?>): Any {
            return try {
                constructor.newInstance(*params)
            } catch (e: InvocationTargetException) {

                throw RuntimeException(
                    java.lang.String.format(
                        "Could not create service of type %s.",
                        format(serviceType)
                    ), e
                )
            } catch (e: Exception) {
                throw RuntimeException(
                    java.lang.String.format(
                        "Could not create service of type %s.",
                        format(serviceType)
                    ), e
                )
            }
        }

        override fun createServiceInstance(): Any {
            val params = parameterTypes

            val invokeArray = arrayOfNulls<Any>(params.size)
            params.forEachIndexed { index, any ->
                val availableService = Optional.ofNullable(owner[unwrap(any)])
                if (!availableService.isPresent) {
                    error("Can't create services")
                }
                invokeArray[index] = availableService.get()
            }


            return invokeMethod(invokeArray)
        }

        override fun getDisplayName(): String {
            return "Service " + format(serviceType)
        }
    }

    private class FactoryService(
        owner: DefaultServiceRegistry,
        override var serviceType: Class<*>,
        private val createMethod: Method,
        private val target: Any
    ) : SingletonService(owner, serviceType) {

        override fun createServiceInstance(): Any? {
            val params = createMethod.parameterTypes//.map { it.type }
            createMethod.isAccessible = true
            val invokeArray = arrayOfNulls<Any>(params.size)
            params.forEachIndexed { index, any ->
                val availableService = Optional.ofNullable(owner[any])
                if (!availableService.isPresent) {
                    error("Can't create services")
                }
                invokeArray[index] = availableService.get()
            }


            return createMethod.invoke(target, *invokeArray)

        }

        override fun getDisplayName(): String {
            return "Service " + serviceType.name + " with implementation " + getInstance()::class.java.name
        }

    }

    private class FixedInstanceService(
        owner: DefaultServiceRegistry,
        override var serviceType: Class<*>,
        serviceInstance: Any
    ) : SingletonService(owner, serviceType) {
        init {
            setInstance(serviceInstance)
        }

        override fun getDisplayName(): String {
            return "Service " + serviceType.name + " with implementation " + getInstance()::class.java.name
        }


        override fun createServiceInstance(): Any {
            throw UnsupportedOperationException()
        }
    }

    private abstract class SingletonService(
        owner: DefaultServiceRegistry,
        override var serviceType: Class<*>
    ) : ManagedObjectServiceProvider(owner) {
        private enum class BindState {
            UNBOUND, BINDING, BOUND
        }

        var state = BindState.UNBOUND


        override fun toString(): String {
            return getDisplayName()
        }

        override fun get(): Any {
            return getInstance()
        }

        private fun prepare(): Service {
            if (state == BindState.BOUND) {
                return this
            }
            synchronized(this) {
                if (state == BindState.BINDING) {
                    throw RuntimeException("Cycle in dependencies of " + getDisplayName() + " detected")
                }
                if (state == BindState.UNBOUND) {
                    state = BindState.BINDING
                    try {
                        bind()
                        state = BindState.BOUND
                    } catch (e: RuntimeException) {
                        state = BindState.UNBOUND
                        throw e
                    }
                }
                return this
            }
        }

        /**
         * Do any preparation work and validation to ensure that [.createServiceInstance] ()} can be called later.
         * This method is never called concurrently.
         */
        protected fun bind() {}


        override fun getService(serviceType: Class<*>): Service? {
            return if (!serviceType.isAssignableFrom(this.serviceType)) {
                null
            } else prepare()
        }


    }


    private abstract class ManagedObjectServiceProvider(protected val owner: DefaultServiceRegistry) :
        ServiceProvider, Service {

        private lateinit var arrayOfService: Array<Service>

        @Volatile
        private var instance: Any? = null

        abstract var serviceType: Class<*>

        protected fun setInstance(instance: Any?) {
            this.instance = instance
            arrayOfService = arrayOf(this)
        }

        override fun getService(serviceType: Class<*>): Service? {
            return this
        }

        override fun getAll(serviceType: Class<*>): Iterator<Service> {
            return arrayOfService.filter { this.serviceType.isAssignableFrom(serviceType) }
                .iterator()
        }

        fun getInstance(): Any {
            var result = instance
            if (instance == null) {
                synchronized(this) {
                    result = instance
                    if (result == null) {
                        setInstance(createServiceInstance())
                        result = instance
                    }
                }
            }
            return checkNotNull(result)
        }

        /**
         * Subclasses implement this method to create the service instance. It is never called concurrently and may not return null.
         */
        protected abstract fun createServiceInstance(): Any?


    }


    /**
     * Wraps a parent to ignore stop requests.
     */
    protected class ParentServices(private val parent: ServiceProvider) :
        ServiceProvider {

        override fun getService(serviceType: Class<*>): Service? {
            return parent.getService(serviceType)
        }

        override fun getAll(serviceType: Class<*>): Iterator<Service> {
            return parent.getAll(serviceType)
        }

    }

    private fun getDisplayName(): String {
        return displayName ?: javaClass.simpleName
    }

    /**
     * Adds a service instance to this registry with the given public type. The given object is closed when this registry is closed.
     */
    fun <T : Any> add(serviceType: Class<out T>, serviceInstance: T): DefaultServiceRegistry {
        ownServices.add(FixedInstanceService(this, serviceType, serviceInstance))
        return this
    }

    /**
     * Adds a service instance to this registry. The given object is closed when this registry is closed.
     */
    fun add(serviceInstance: Any): DefaultServiceRegistry {
        return add(serviceInstance.javaClass, serviceInstance)
    }

    /**
     * Adds a service provider bean to this registry. This provider may define factory and decorator methods.
     */
    fun addProvider(provider: Any): DefaultServiceRegistry {
        findProviderMethods(provider)
        return this
    }


    override fun <T> get(serviceType: Class<T>): T {
        val instance = find(serviceType)
        return checkNotNull(instance) as T
    }

    override fun <T> getAll(serviceType: Class<T>): List<T> {
        return allServices.getAll(serviceType)
            .asSequence()
            .mapNotNull {
                runCatching {
                    it.get() as T
                }.getOrNull()
            }.toList()
    }

    override fun find(serviceType: Class<*>): Any? {
        return ownServices.getService(serviceType)?.get()
    }

    override fun asProvider(): ServiceProvider {
        return allServices
    }

    override fun toString(): String {
        return getDisplayName()
    }
}

fun unwrap(type: Type): Class<*> {
    return if (type is Class<*>) {
        type as Class<*>
    } else {
        if (type is WildcardType) {
            val wildcardType: WildcardType = type as WildcardType
            if (wildcardType.getUpperBounds()
                    .get(0) is Class<*> && wildcardType.getLowerBounds().size === 0
            ) {
                return wildcardType.getUpperBounds().get(0) as Class<*>
            }
        }
        val parameterizedType = type as ParameterizedType
        parameterizedType.getRawType() as Class<*>
    }
}