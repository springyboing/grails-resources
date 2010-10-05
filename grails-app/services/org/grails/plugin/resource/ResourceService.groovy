package org.grails.plugin.resource

import java.util.concurrent.ConcurrentHashMap

import grails.util.Environment
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsWebRequest
import org.codehaus.groovy.grails.web.context.ServletContextHolder
import org.springframework.web.util.WebUtils
import org.grails.resources.ResourceModulesBuilder
import org.apache.commons.io.FilenameUtils
import javax.servlet.ServletRequest
import grails.util.Environment
import org.springframework.util.AntPathMatcher


/**
 * @todo Move all this code out into a standard Groovy bean class and declare the bean in plugin setup
 * so that if this is pulled into core, other plugins are not written to depend on this service
 */
class ResourceService {

    def pluginManager
    
    static transactional = false

    static IMPLICIT_MODULE = "__@legacy-files@__"
    static REQ_ATTR_DEBUGGING = 'resources.debug'
    
    static DEFAULT_MODULE_SETTINGS = [
        css:[disposition: 'head'],
        rss:[disposition: 'head'],
        gif:[disposition: 'head'],
        jpg:[disposition: 'head'],
        png:[disposition: 'head'],
        ico:[disposition: 'head'],
        js:[disposition: 'defer']
    ]

    def staticUrlPrefix
    
    @Lazy File workDir = new File(WebUtils.getTempDir(ServletContextHolder.servletContext), "grails-resources")
    
    def modulesByName = [:]

    def processedResourcesByURI = new ConcurrentHashMap()

    def moduleNamesByBundle = [:]
    
    List resourceMappers = []
    
    def grailsApplication
    
    /**
     * Process a legacy URI that points to a normal resource, not produced with our
     * own tags, and likely not referencing a declared resource.
     * Therefore the URI may not be build-unique and cannot reliably be cached so
     * we have to redirect "Moved Temporarily" to it in case another plugin causes eternal caching etc.
     *
     * To do this, we simply search the cache based on sourceUrl instead of actualUrl
     *
     * This is not recommended, its just a quick out of the box fix for legacy (or pre-"resources plugin" plugin) code.
     *
     * So a request for <ctx>/css/main.css comes in. This needs to redirect to e.g. <ctx>/static/css/342342353345343534.css
     * This involves looking it up by source uri. Therefore the same resource may have multiple mappings in the 
     * processedResourcesByURI map but they should not be conflicting.
     */
    void processAdHocResource(request, response) {
        if (log.debugEnabled) {
            log.debug "Handling ad-hoc resource ${request.requestURI}"
        }
        def uri = ResourceService.removeQueryParams(request.requestURI[request.contextPath.size()..-1])
        // @todo query params are lost at this point for ad hoc resources, this needs fixing
        def res = getResourceMetaForURI(uri, false)
        if (Environment.current == Environment.DEVELOPMENT) {
            response.setHeader('X-Grails-Resources-Original-Src', res.sourceUrl)
        }
        if (res?.exists()) {
            redirectToActualUrl(res, request, response)
        } else {
            response.sendError(404)
        }
    }
    
    /**
     * Redirect the client to the actual processed Url, used for when an ad-hoc resource is accessed
     */
    void redirectToActualUrl(ResourceMeta res, request, response) {
        // Now redirect the client to the processed url
        def u = request.contextPath+staticUrlPrefix+res.linkUrl
        if (log.debugEnabled) {
            log.debug "Redirecting ad-hoc resource ${request.requestURI} to $u which makes it UNCACHEABLE - declare this resource "+
                "and use resourceLink/module tags to avoid redirects and enable client-side caching"
        }
        response.sendRedirect(u)
    }
    
    /**
     * Process a URI where the input URI matches a cached and declared resource URI,
     * without any redirects. This is the real deal
     */
    void processDeclaredResource(request, response) {
        if (log.debugEnabled) {
            log.debug "Handling resource ${request.requestURI}"
        }
        // Find the ResourceMeta for the request, or create it
        def uri = ResourceService.removeQueryParams(request.requestURI[(request.contextPath+staticUrlPrefix).size()..-1])
        def inf = getResourceMetaForURI(uri)
        
        if (Environment.current == Environment.DEVELOPMENT) {
            response.setHeader('X-Grails-Resources-Original-Src', inf?.sourceUrl)
        }

        // See if its an ad-hoc resource that has come here via a relative link
        // @todo make this development mode only by default?
        if (inf.actualUrl != uri) {
            redirectToActualUrl(inf, request, response)
            return
        }

        // If we have a file, go for it
        if (inf?.exists()) {
            if (log.debugEnabled) {
                log.debug "Returning processed resource ${request.requestURI}"
            }
            def data = inf.processedFile.newInputStream()
            try {
                // Now set up the response
                response.contentType = inf.contentType
                response.setContentLength(inf.processedFile.size().toInteger())

                // Here we need to let the mapper add headers etc
                if (inf.requestProcessors) {
                    if (log.debugEnabled) {
                        log.debug "Running request processors on ${request.requestURI}"
                    }
                    inf.requestProcessors.each { processor ->
                        if (log.debugEnabled) {
                            log.debug "Applying request processor on ${request.requestURI}: "+processor.class.name
                        }
                        def p = processor.clone()
                        p.delegate = inf
                        p(request, response)
                    }
                }
                
                // Could we do something faster here?
                response.outputStream << data
            } finally {
                data?.close()
            }
        } else {
            response.sendError(404)
        }
    }
    
    /**
     * Get the existing or create a new ad-hoc ResourceMeta for the URI.
     * @returns The resource instance - which may have a null processedFile if the resource cannot be found
     */
    ResourceMeta getResourceMetaForURI(uri, adHocResource = true, Closure postProcessor = null) {
        def r = processedResourcesByURI[uri]

        // If we don't already have it, its not been declared in the DSL and its
        // not already been retrieved
        if (!r) {
            // We often get multiple simultaneous requests at startup and this causes
            // multiple creates and loss of concurrently processed resources
            def mod
            synchronized (IMPLICIT_MODULE) {
                mod = getModule(IMPLICIT_MODULE)
                if (!mod) {
                    if (log.debugEnabled) {
                        log.debug "Creating implicit module"
                    }
                    defineModule(IMPLICIT_MODULE)
                    mod = getModule(IMPLICIT_MODULE)
                }
            }
            
            // Need to put in cache
            if (log.debugEnabled) {
                log.debug "Creating new implicit resource for ${uri}"
            }
            r = new ResourceMeta(sourceUrl: uri, workDir: workDir)
        
            // Do the processing
            // @todo we should really sync here on something specific to the resource
            prepareResource(r, adHocResource)
        
            // Only if the URI mapped to a real file, do we add the resource
            // Prevents DoS with zillions of 404s
            if (r.exists()) {
                if (postProcessor) {
                    postProcessor(r)
                }
                synchronized (mod.resources) {
                    // Prevent concurrent requests resulting in multiple additions of same resource
                    if (!mod.resources.find({ x -> x.sourceUrl == r.sourceUrl }) ) {
                        mod.resources << r
                    }
                }
            }
        }
        return r
    }
    
    /**
     * Execute the processing chain for the resource
     */
    void prepareResource(ResourceMeta r, boolean adHocResource) {
        if (log.debugEnabled) {
            log.debug "Preparing resource ${r.sourceUrl}"
        }
        def uri = r.sourceUrl
        def origResource = ServletContextHolder.servletContext.getResourceAsStream(uri)
        if (!origResource) {
            if (log.errorEnabled) {
                log.error "Resource not found: ${uri}"
            }
            throw new IllegalArgumentException("Cannot locate resource [$uri]")
        }
        
        r.contentType = ServletContextHolder.servletContext.getMimeType(uri)
        if (log.debugEnabled) {
            log.debug "Resource [$uri] has content type [${r.contentType}]"
        }

        try {
            def fileSystemDir = uri[0..uri.lastIndexOf('/')-1].replaceAll('/', File.separator)
            def fileSystemFile = uri[uri.lastIndexOf('/')+1..-1].replaceAll('/', File.separator)
            def staticDir = new File(workDir, fileSystemDir)
            
            // force the structure
            if (!staticDir.exists()) {
                // Do not assert this, we are re-entrant and may get multiple simultaneous calls.
                // We just want to be sure one of them works
                staticDir.mkdirs()
                if (!staticDir.exists()) {
                    log.error "Unable to create static resource cache directory: ${staticDir}"
                }
            }
            
            // copy the file ready for mutation
            r.processedFile = new File(staticDir, fileSystemFile)
            // Delete the existing file - it may be from previous release, we cannot tell.
            if (r.processedFile.exists()) {
                assert r.processedFile.delete()
            }
            
            r.actualUrl = r.sourceUrl

            // Now copy in the resource from this app deployment into the cache, ready for mutation
            r.processedFile << origResource

            // Now iterate over the mappers...
            if (log.debugEnabled) {
                log.debug "Applying mappers to ${r.processedFile}"
            }
            
            def mappers = resourceMappers.sort({it.order})

            // Build up list of excludes patterns for each mapper name
            def mapperExcludes = [:]
            resourceMappers.each { m -> 
                // @todo where do we get defaults from? preferably from each plugin...
                def patterns = getConfigParamOrDefault("${m.name}.excludes", [])
                mapperExcludes[m.name] = patterns
            }
            
            def antMatcher = new AntPathMatcher()
            
            mappers.eachWithIndex { mapperInfo, i ->
                def excludes = mapperExcludes[mapperInfo.name]
                if (excludes) {
                    if (excludes.any { pattern -> antMatcher.match(pattern, r.sourceUrl) }) {
                        if (log.debugEnabled) {
                            log.debug "Skipping static content mapper [${mapperInfo.name}] for ${r.sourceUrl} due to excludes pattern ${excludes}"
                        }
                        return // Skip this resource, it is excluded
                    }
                }

                if (log.debugEnabled) {
                    log.debug "Applying static content mapper [${mapperInfo.name}] to ${r.dump()}"
                }

                // Apply mapper if not suppressed for this resource - check attributes
                if (!r.attributes['no'+mapperInfo.name]) {
                    def prevFile = r.processedFile.toString()
                    if (mapperInfo.mapper.maximumNumberOfParameters == 1) {
                        mapperInfo.mapper(r) 
                    } else {
                        mapperInfo.mapper(r, this) 
                    }
                    
                    // Flag that this mapper has been applied
                    r.attributes['+'+mapperInfo.name] = true
                }

                if (log.debugEnabled) {
                    log.debug "Done applying static content mapper [${mapperInfo.name}] to ${r.dump()}"
                }
            }
            
            if (log.debugEnabled) {
                log.debug "Updating URI to resource cache for ${r.actualUrl} >> ${r.processedFile}"
            }
            
            // Add the actual linking URL to the cache so resourceLink resolves
            processedResourcesByURI[r.actualUrl] = r
            
            // Add the original source url to the cache as well, if it was an ad-hoc resource
            // As the original URL is used, we need this to resolve to the actualUrl for redirect
            if (adHocResource) {
                processedResourcesByURI[r.sourceUrl] = r
            }
        } finally {
            origResource?.close()
        }
    }
    
    /**
     * Resource mappers can mutate URLs any way they like. They are exeecuted in the order
     * registered, so plugins must use dependsOn & loadAfter to set their ordering correctly before
     * they register with us
     * The closure takes 1 arg - the current resource. Any mutations can be performed by 
     * changing actualUrl or processedFile or other propertis of ResourceMeta
     */    
    void addResourceMapper(String name, Closure mapper, Integer order = 10000) {
        resourceMappers << [name:name, mapper:mapper, order:order]
    }
    
    void storeModule(ResourceModule m) {
        if (log.debugEnabled) {
            log.debug "Storing resource module definition ${m.dump()}"
        }
        
        m.resources.each { r ->
            prepareResource(r, false)
        }
        modulesByName[m.name] = m
    }
    
    def defineModule(String name) {
        storeModule(new ResourceModule(name, this))
    }

    def module(String name, String url) {
        storeModule(new ResourceModule(name, [url:url], this))
    }

    def module(String name, Map urlInfo) {
        storeModule(new ResourceModule(name, urlInfo, this))
    }

    def module(String name, List urlsOrInfos) {
        storeModule(new ResourceModule(name, urlsOrInfos, this))
    }

    def module(String name, List urlsOrInfos, List moduleDeps) {
        def m = new ResourceModule(name, urlsOrInfos, this)
        storeModule(m)
        moduleDeps?.each { d ->
            m.addModuleDependency(d)
        }
    }
    
    /**
     * Resolve a resource to a URL by resource name
     */
    def getModule(name) {
        modulesByName[name]
    }
        
    void forgetResources() {
        modulesByName.clear()
        processedResourcesByURI.clear()
    }
    
    void loadResourcesFromConfig() {
        forgetResources()
        
        // Placeholder code, we might support lists of config closures in future
        def modules = config.modules
        if (!(modules instanceof Closure)) {
            return
        }
        def moduleClosures = [modules]
        if (log.debugEnabled) {
            log.debug "Loading resource module definitions from Config... "+moduleClosures
        }
        moduleClosures?.each { clo ->
            def builder = new ResourceModulesBuilder()
            clo.delegate = builder
            clo.resolveStrategy = Closure.DELEGATE_FIRST
            clo()
            
            if (log.debugEnabled) {
                log.debug "Resource module definitions for [${builder._modules}] found in Config..."
            }
            builder._modules.each { m ->
                module(m.name, m.resources, m.depends)
            }
        }
    }
    
    static removeQueryParams(uri) {
        def qidx = uri.indexOf('?')
        qidx > 0 ? uri[0..qidx-1] : uri
    }
    
    def getDefaultSettingsForURI(uri, typeOverride = null) {
        
        if (!typeOverride) {
            // Strip off query args
            def extUrl = ResourceService.removeQueryParams(uri)
            
            def ext = FilenameUtils.getExtension(extUrl)
            if (log.debugEnabled) {
                log.debug "Extension extracted from ${uri} ([$extUrl]) is ${ext}"
            }
            typeOverride = ext
        }
        
        DEFAULT_MODULE_SETTINGS[typeOverride]
    }
    
    
    def dumpResources(toLog = true) {
        def s1 = new StringBuilder()
        modulesByName.keySet().sort().each { moduleName ->
            def mod = modulesByName[moduleName]
            s1 << "Module: ${moduleName}\n"
            s1 << "   Depends on modules: ${mod.dependsOn}\n"
            def res = []+mod.resources
            res.sort({ a,b -> a.actualUrl <=> b.actualUrl}).each { resource ->
                s1 << "   Resource: ${resource.sourceUrl}\n"
                s1 << "             -- local file: ${resource.processedFile}\n"
                s1 << "             -- mime type: ${resource.contentType}\n"
                s1 << "             -- processed Url: ${resource.actualUrl}\n"
                s1 << "             -- url for linking: ${resource.linkUrl}\n"
                s1 << "             -- url override: ${resource.linkOverride}\n"
                s1 << "             -- attributes: ${resource.attributes}\n"
                s1 << "             -- tag attributes: ${resource.tagAttributes}\n"
                s1 << "             -- disposition: ${resource.disposition}\n"
            }
        }
        def s2 = new StringBuilder()
        processedResourcesByURI.keySet().sort().each { uri ->
            def res = processedResourcesByURI[uri]
            s2 << "Request URI: ${uri} => ${res.processedFile}\n"
        }
        if (toLog) {
            log.debug '-'*50
            log.debug "Resource definitions"
            log.debug(s1)
            log.debug '-'*50
            log.debug "Resource URI cache"
            log.debug '-'*50
            log.debug(s2)
            log.debug '-'*50
        }
        return s1.toString() + s2.toString()
    }
    
    /**
     * Returns the config object under 'grails.resources'
     */
    ConfigObject getConfig() {
        grailsApplication.config.grails.resources
    }
    
    /**
     * Used to retrieve a resources config param, or return the supplied
     * default value if no explicit value was set in config
     */
    def getConfigParamOrDefault(String key, defaultValue) {
        def param = key.tokenize('.').inject(config) { conf, v -> conf[v] }

        if (param instanceof ConfigObject) {
            param.size() == 0 ? defaultValue : param
        } else {
            param
        }
    }
    
    boolean isDebugMode(ServletRequest request) {
        if (getConfigParamOrDefault('debug', false)) {
            config.debug
        } else if (request != null) {
            isExplicitDebugRequest(request)
        } else {
            false
        }
    }
    
    private isExplicitDebugRequest(ServletRequest request) {
        if (Environment.current == Environment.DEVELOPMENT) {
            def requestContainsDebug = request.getParameter('debug') != null
            def wasReferredFromDebugRequest = request.getHeader('Referer')?.contains('?debug=')

            requestContainsDebug || wasReferredFromDebugRequest
        } else {
            false
        }
    }
}
