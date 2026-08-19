package net.filebot.web;

import static java.nio.charset.StandardCharsets.*;
import static java.util.Arrays.*;
import static java.util.Collections.*;
import static java.util.stream.Collectors.*;
import static net.filebot.CachedResource.fetchIfModified;
import static net.filebot.Logging.*;
import static net.filebot.util.JsonUtilities.*;
import static net.filebot.util.StringUtilities.*;
import static net.filebot.web.EpisodeUtilities.*;
import static net.filebot.web.WebRequest.*;

import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import javax.swing.Icon;

import net.filebot.Cache;
import net.filebot.CacheType;
import net.filebot.ResourceManager;

public class TheTVDBClient extends AbstractEpisodeListProvider implements ArtworkProvider {

	private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

	private String apikey;
	private String pin;

	public TheTVDBClient(String apikey) {
		this(apikey, null);
	}

	public TheTVDBClient(String apikey, String pin) {
		this.apikey = apikey;
		this.pin = pin;
	}

	@Override
	public String getIdentifier() {
		return "TheTVDB";
	}

	@Override
	public Icon getIcon() {
		return ResourceManager.getIcon("search.thetvdb");
	}

	@Override
	public boolean hasSeasonSupport() {
		return true;
	}

	protected Object postJson(String path, Object object) throws Exception {
		// curl -X POST --header 'Content-Type: application/json' --header 'Accept: application/json' 'https://api4.thetvdb.com/v4/login' --data '{"apikey":"XXXXX"}'
		ByteBuffer response = post(getEndpoint(path), json(object, false).getBytes(UTF_8), "application/json", null);
		return readJson(UTF_8.decode(response));
	}

	protected Object requestJson(String path, Duration expirationTime) throws Exception {
		Cache cache = Cache.getCache(getName(), CacheType.Monthly);
		return cache.json(path, this::getEndpoint).fetch(fetchIfModified(this::getRequestHeader)).expire(expirationTime).get();
	}

	protected URL getEndpoint(String path) throws Exception {
		return new URL("https://api4.thetvdb.com/v4/" + path);
	}

	private Map<String, String> getRequestHeader() {
		Map<String, String> header = new LinkedHashMap<String, String>(2);
		header.put("Accept", "application/json");
		header.put("Authorization", "Bearer " + getAuthorizationToken());

		return header;
	}

	private Optional<String> getLanguageCode(Locale locale) {
		return Optional.ofNullable(locale).filter(l -> !l.getLanguage().isEmpty()).map(l -> {
			try {
				return l.getISO3Language();
			} catch (Exception e) {
				return null;
			}
		});
	}

	private String token = null;
	private Instant tokenExpireInstant = null;
	private Duration tokenExpireDuration = Duration.ofDays(27); // token expires after 1 month

	private String getAuthorizationToken() {
		synchronized (tokenExpireDuration) {
			if (token == null || (tokenExpireInstant != null && Instant.now().isAfter(tokenExpireInstant))) {
				try {
					Map<String, Object> credentials = new LinkedHashMap<String, Object>(2);
					credentials.put("apikey", apikey);
					if (pin != null && pin.length() > 0) {
						credentials.put("pin", pin);
					}
					Object json = postJson("login", credentials);
					token = getString(getMap(json, "data"), "token");
					tokenExpireInstant = Instant.now().plus(tokenExpireDuration);
				} catch (Exception e) {
					throw new IllegalStateException("Failed to retrieve authorization token: " + e.getMessage(), e);
				}
			}
			return token;
		}
	}

	protected List<SearchResult> search(String path, Map<String, Object> query, Locale locale, Duration expirationTime) throws Exception {
		Object json = requestJson(path + "?" + encodeParameters(query, true), expirationTime);

		return streamJsonObjects(json, "data").map(it -> {
			Integer id = getInteger(it, "tvdb_id");
			if (id == null) {
				return null;
			}
			String seriesName = getString(it, "name");
			Optional<String> languageCode = getLanguageCode(locale);
			Map<?, ?> translations = getMap(it, "translations");
			if (languageCode.isPresent()) {
				String localizedName = getString(translations, languageCode.get());
				if (localizedName != null && localizedName.length() > 0) {
					seriesName = localizedName;
				}
			}
			String[] aliasNames = stream(getArray(it, "aliases")).filter(Objects::nonNull).map(Object::toString).toArray(String[]::new);

			if (seriesName == null || seriesName.startsWith("**") || seriesName.endsWith("**")) {
				debug.warning(format("Ignore invalid series: %s [%d]", seriesName, id));
				return null;
			}

			return new SearchResult(id, seriesName, aliasNames);
		}).filter(Objects::nonNull).collect(toList());
	}

	@Override
	public List<SearchResult> fetchSearchResult(String query, Locale locale) throws Exception {
		Map<String, Object> parameters = new LinkedHashMap<String, Object>(2);
		parameters.put("query", query);
		parameters.put("type", "series");
		return search("search", parameters, locale, Cache.ONE_DAY);
	}

	@Override
	public TheTVDBSeriesInfo getSeriesInfo(int id, Locale language) throws Exception {
		return getSeriesInfo(new SearchResult(id), language);
	}

	@Override
	public TheTVDBSeriesInfo getSeriesInfo(SearchResult series, Locale locale) throws Exception {
		Object json = requestJson("series/" + series.getId() + "/extended", Cache.ONE_WEEK);
		Object data = getMap(json, "data");

		TheTVDBSeriesInfo info = new TheTVDBSeriesInfo(this, locale, series.getId());
		info.setSlug(getString(data, "slug"));
		info.setAliasNames(Stream.concat(Stream.of(series.getAliasNames()), streamJsonObjects(data, "aliases").map(a -> getString(a, "name"))).filter(Objects::nonNull).distinct().toArray(String[]::new));

		String name = getString(data, "name");
		String overview = getString(data, "overview");
		Optional<String> languageCode = getLanguageCode(locale);
		if (languageCode.isPresent()) {
			try {
				Object translation = getMap(requestJson("series/" + series.getId() + "/translations/" + languageCode.get(), Cache.ONE_WEEK), "data");
				String translatedName = getString(translation, "name");
				String translatedOverview = getString(translation, "overview");
				if (translatedName != null && translatedName.length() > 0) name = translatedName;
				if (translatedOverview != null && translatedOverview.length() > 0) overview = translatedOverview;
			} catch (Exception e) {
				debug.finest(cause("Failed to retrieve series translation", e));
			}
		}
		info.setName(name);
		info.setOverview(overview);
		info.setCertification(streamJsonObjects(data, "contentRatings").map(r -> getString(r, "name")).filter(Objects::nonNull).findFirst().orElse(null));
		info.setNetwork(getString(getMap(data, "latestNetwork"), "name"));
		info.setStatus(getString(getMap(data, "status"), "name"));

		info.setRating(getDouble(data, "score"));
		info.setRatingCount(null);

		info.setRuntime(getInteger(data, "averageRuntime"));
		info.setGenres(streamJsonObjects(data, "genres").map(g -> getString(g, "name")).filter(Objects::nonNull).collect(toList()));
		info.setStartDate(getStringValue(data, "firstAired", SimpleDate::parse));

		// TheTVDB SeriesInfo extras
		info.setImdbId(streamJsonObjects(data, "remoteIds").filter(r -> "IMDB".equalsIgnoreCase(getString(r, "sourceName"))).map(r -> getString(r, "id")).findFirst().orElse(null));
		info.setAirsDayOfWeek(getString(data, "airsDayOfWeek"));
		info.setAirsTime(getString(data, "airsTime"));
		info.setBannerUrl(getStringValue(data, "image", this::resolveImage));
		info.setLastUpdated(getStringValue(data, "lastUpdated", TheTVDBClient::parseTimestamp));

		return info;
	}

	@Override
	protected SeriesData fetchSeriesData(SearchResult series, SortOrder sortOrder, Locale locale) throws Exception {
		// fetch series info
		SeriesInfo info = getSeriesInfo(series, locale);
		info.setOrder(sortOrder.name());

		// ignore preferred language if basic series information isn't even available
		if (info.getName() == null && !locale.equals(DEFAULT_LOCALE)) {
			return fetchSeriesData(series, sortOrder, DEFAULT_LOCALE);
		}

		// if series name isn't even available in English then just use whatever value we've got
		if (info.getName() == null) {
			info.setName(series.getName());
		}

		// fetch episode data
		List<Episode> episodes = new ArrayList<Episode>();
		List<Episode> specials = new ArrayList<Episode>();

		String seasonType = sortOrder == SortOrder.DVD ? "dvd" : "default";
		String episodesPath = "series/" + series.getId() + "/episodes/" + seasonType + "/" + getLanguageCode(locale).orElse("eng");

		for (int page = 0;; page++) {
			Object json = requestJson(episodesPath + "?page=" + page, Cache.ONE_DAY);
			Object data = getMap(json, "data");

			streamJsonObjects(data, "episodes").forEach(it -> {
				Integer id = getInteger(it, "id");
				String episodeName = getString(it, "name");

				// default to English episode title if the preferred language is not available
				if (episodeName == null && !locale.equals(DEFAULT_LOCALE)) {
					try {
						episodeName = getEpisodeList(series, sortOrder, DEFAULT_LOCALE).stream().filter(e -> id.equals(e.getId())).findFirst().map(Episode::getTitle).orElse(null);
					} catch (Exception e) {
						debug.warning(cause("Failed to retrieve default episode title", e));
					}
				}

				Integer absoluteNumber = getInteger(it, "absoluteNumber");
				SimpleDate airdate = getStringValue(it, "aired", SimpleDate::parse);

				// default numbering
				Integer episodeNumber = getInteger(it, "number");
				Integer seasonNumber = getInteger(it, "seasonNumber");

				// adjust for forced absolute numbering (if possible)
				if (sortOrder == SortOrder.DVD) {
					Integer dvdSeasonNumber = getInteger(it, "seasonNumber");
					Number dvdEpisodeNumber = getDecimal(it, "number");

					// require both values to be valid integer numbers
					if (dvdSeasonNumber != null && dvdEpisodeNumber != null) {
						seasonNumber = dvdSeasonNumber;
						episodeNumber = dvdEpisodeNumber.intValue();

						if (episodeNumber.doubleValue() != dvdEpisodeNumber.doubleValue()) {
							debug.finest(format("[%s] Coerce episode number [%s] to [%s]", info, dvdEpisodeNumber, episodeNumber));
						}
					}
				} else if (sortOrder == SortOrder.Absolute && absoluteNumber != null && absoluteNumber > 0) {
					seasonNumber = null;
					episodeNumber = absoluteNumber;
				} else if (sortOrder == SortOrder.AbsoluteAirdate && airdate != null) {
					// use airdate as absolute episode number
					seasonNumber = null;
					episodeNumber = airdate.getYear() * 1_00_00 + airdate.getMonth() * 1_00 + airdate.getDay();
				}

				if (seasonNumber == null || seasonNumber > 0) {
					// handle as normal episode
					episodes.add(new Episode(info.getName(), seasonNumber, episodeNumber, episodeName, absoluteNumber, null, airdate, id, new SeriesInfo(info)));
				} else {
					// handle as special episode
					specials.add(new Episode(info.getName(), null, null, episodeName, absoluteNumber, episodeNumber, airdate, id, new SeriesInfo(info)));
				}
			});

			if (asMap(getMap(json, "links")).get("next") == null) {
				break;
			}
		}

		// episodes my not be ordered by DVD episode number
		episodes.sort(episodeComparator());

		// add specials at the end
		episodes.addAll(specials);

		return new SeriesData(info, episodes);
	}

	public SearchResult lookupByID(int id, Locale locale) throws Exception {
		if (id <= 0) {
			throw new IllegalArgumentException("Illegal TheTVDB ID: " + id);
		}

		SeriesInfo info = getSeriesInfo(new SearchResult(id), locale);
		return new SearchResult(id, info.getName(), info.getAliasNames());
	}

	public SearchResult lookupByIMDbID(int imdbid, Locale locale) throws Exception {
		if (imdbid <= 0) {
			throw new IllegalArgumentException("Illegal IMDbID ID: " + imdbid);
		}

		Object json = requestJson("search/remoteid/" + String.format("tt%07d", imdbid), Cache.ONE_MONTH);
		return streamJsonObjects(json, "data").map(it -> getMap(it, "series")).filter(it -> !it.isEmpty()).map(it -> {
			Integer id = getInteger(it, "id");
			return id == null ? null : new SearchResult(id, getString(it, "name"));
		}).filter(Objects::nonNull).findFirst().orElse(null);
	}

	@Override
	public URI getEpisodeListLink(SearchResult searchResult) {
		return URI.create("https://www.thetvdb.com/?tab=seasonall&id=" + searchResult.getId());
	}

	@Override
	public List<Artwork> getArtwork(int id, String category, Locale locale) throws Exception {
		Map<String, Object> parameters = new LinkedHashMap<String, Object>(2);
		Integer typeId = getArtworkTypeIds().get(category == null ? null : category.toLowerCase());
		if (typeId != null) parameters.put("type", typeId);
		getLanguageCode(locale).ifPresent(lang -> parameters.put("lang", lang));
		String query = parameters.isEmpty() ? "" : "?" + encodeParameters(parameters, true);
		Object json = requestJson("series/" + id + "/artworks" + query, Cache.ONE_MONTH);
		Object data = getMap(json, "data");

		return streamJsonObjects(data, "artworks").map(it -> {
			URL url = getStringValue(it, "image", this::resolveImage);
			Double rating = getDouble(it, "score");
			Integer width = getInteger(it, "width");
			Integer height = getInteger(it, "height");
			String resolution = width != null && height != null ? width + "x" + height : null;
			boolean includesText = Boolean.TRUE.equals(asMap(it).get("includesText"));

			return new Artwork(Stream.of(category, includesText ? "text" : "graphical", resolution), url, locale, rating);
		}).sorted(Artwork.RATING_ORDER).collect(toList());
	}

	private Map<String, Integer> getArtworkTypeIds() throws Exception {
		Object json = requestJson("artwork/types", Cache.ONE_MONTH);
		Map<String, Integer> types = new LinkedHashMap<String, Integer>();
		streamJsonObjects(json, "data").forEach(it -> {
			String name = getString(it, "name");
			Integer id = getInteger(it, "id");
			if (name != null && id != null) types.put(name.toLowerCase(), id);
		});
		return types;
	}

	private static Long parseTimestamp(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		try {
			return Instant.parse(value).toEpochMilli();
		} catch (Exception e) {
			try {
				return LocalDate.parse(value.substring(0, Math.min(10, value.length()))).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
			} catch (Exception ignored) {
				return null;
			}
		}
	}

	protected URL resolveImage(String path) {
		if (path == null || path.isEmpty()) {
			return null;
		}

		try {
			return new URL(path.startsWith("http://") || path.startsWith("https://") ? path : "https://artworks.thetvdb.com/" + (path.startsWith("/") ? path.substring(1) : path));
		} catch (Exception e) {
			throw new IllegalArgumentException(path, e);
		}
	}

	public List<String> getLanguages() throws Exception {
		Object response = requestJson("languages", Cache.ONE_MONTH);
		return streamJsonObjects(response, "data").map(it -> getString(it, "shortCode")).filter(Objects::nonNull).collect(toList());
	}

	public List<Person> getActors(int seriesId, Locale locale) throws Exception {
		Object response = requestJson("series/" + seriesId + "/extended", Cache.ONE_MONTH);
		Object data = getMap(response, "data");

		// e.g. [id:68414, seriesId:78874, name:Summer Glau, role:River Tam, sortOrder:2, image:actors/68414.jpg, imageAuthor:513, imageAdded:0000-00-00 00:00:00, lastUpdated:2011-08-18 11:53:14]
		return streamJsonObjects(data, "characters").filter(it -> "Actor".equalsIgnoreCase(getString(it, "peopleType"))).map(it -> {
			String name = getString(it, "personName");
			String character = getString(it, "name");
			Integer order = getInteger(it, "sort");
			URL image = getStringValue(it, "personImgURL", this::resolveImage);

			return new Person(name, character, Person.ACTOR, null, order, image);
		}).sorted(Person.CREDIT_ORDER).collect(toList());
	}

	public EpisodeInfo getEpisodeInfo(int id, Locale locale) throws Exception {
		Object response = requestJson("episodes/" + id + "/extended", Cache.ONE_MONTH);
		Object data = getMap(response, "data");

		Integer seriesId = getInteger(data, "seriesId");
		String overview = getString(data, "overview");

		Double rating = getDouble(data, "score");
		Integer votes = null;

		List<Person> people = new ArrayList<Person>();

		streamJsonObjects(data, "characters").forEach(it -> {
			String type = getString(it, "peopleType");
			String name = getString(it, "personName");
			if (name == null || type == null) return;
			if (Person.DIRECTOR.equalsIgnoreCase(type)) people.add(new Person(name, Person.DIRECTOR));
			else if (Person.WRITER.equalsIgnoreCase(type)) people.add(new Person(name, Person.WRITER));
			else if (Person.GUEST_STAR.equalsIgnoreCase(type)) people.add(new Person(name, getString(it, "name"), Person.GUEST_STAR, null, getInteger(it, "sort"), getStringValue(it, "personImgURL", this::resolveImage)));
		});

		return new EpisodeInfo(this, locale, seriesId, id, people, overview, rating, votes);
	}

}
