package skaro.pokeapi;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.cache.CacheManager;

import reactor.cache.CacheMono;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import skaro.pokeapi.resource.NamedApiResource;
import skaro.pokeapi.resource.NamedApiResourceList;

public class ReactiveCachingPokeApiClient implements PokeApiClient {
	Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private PokeApiEntityFactory entityFactory;	
	private CacheManager cacheManager;

	public ReactiveCachingPokeApiClient(PokeApiEntityFactory entityFactory, CacheManager cacheManager) {
		this.entityFactory = entityFactory;
		this.cacheManager = cacheManager;
	}

	@Override
	public <T extends PokeApiResource> Mono<T> getResource(Class<T> cls, String idOrName) {
		 return CacheMono.lookup(key -> checkCache(cls, key), idOrName)
				 .onCacheMissResume(() -> entityFactory.getResource(cls, idOrName))
				 .andWriteWith((key, value) -> writeToCache(cls, key, value));
	}
	
	private <T> Mono<Signal<? extends T>> checkCache(Class<T> cls, String key) {
		Optional<Signal<? extends T>> resourceFromCache = Optional.of(cacheManager.getCache(cls.getName()))
				.map(cache -> cache.get(key))
				.map(ValueWrapper::get)
				.map(Signal.class::cast);
		
		return Mono.<Signal<? extends T>>justOrEmpty(resourceFromCache);
	}
	
	private <T> Mono<Void> writeToCache(Class<T> cls, String key, Signal<? extends T> value) {
		Consumer<Cache> writeToCache = cache -> cache.put(key, value);
		Runnable logCacheFailure = () -> LOG.warn("Cache '{}' does not exist. Could not cache PokeApi resource. Please ensure cache '{}' exists or allow lazy creation of caches.", 
				cls.getName(), 
				cls.getName());
		
		return Mono.fromRunnable(() -> {
			Optional.ofNullable(cacheManager.getCache(cls.getName()))
				.ifPresentOrElse(writeToCache, logCacheFailure);
		});
	}

	@Override
	public <T extends PokeApiResource> Mono<NamedApiResourceList<T>> getResource(Class<T> cls) {
		return entityFactory.getBaseResource(cls);
	}

	@Override
	public <T extends PokeApiResource> Mono<NamedApiResourceList<T>> getResource(Class<T> cls, PageQuery query) {
		return entityFactory.getBaseResource(cls, query);
	}

	@Override
	public <T extends PokeApiResource> Mono<T> followResource(Supplier<NamedApiResource<T>> resourceSupplier, Class<T> cls) {
		return Mono.fromSupplier(resourceSupplier)
				.flatMap(resource -> entityFactory.getNamedResource(resource, cls));
	}

	@Override
	public <T extends PokeApiResource> Flux<T> followResources(Supplier<List<NamedApiResource<T>>> resourcesSupplier, Class<T> cls) {
		return Mono.fromSupplier(resourcesSupplier)
				.flatMapMany(resources -> entityFactory.getNamedResources(resources, cls));
	}

}
