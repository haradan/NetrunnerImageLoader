package uk.co.haradan.octgnimageloader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLConnection;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.commons.io.IOUtils;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import uk.co.haradan.octgnimageloader.cardkeys.CardKey;
import uk.co.haradan.octgnimageloader.cardkeys.CardKeyBuilderConfig;
import uk.co.haradan.octgnimageloader.cardkeys.JsonCardKeyBuilder;
import uk.co.haradan.octgnimageloader.cardkeys.OctgnCardKeyBuilder;
import uk.co.haradan.octgnimageloader.config.OCTGNImageLoaderConfig;
import uk.co.haradan.octgnimageloader.config.SetSelector;
import uk.co.haradan.octgnimageloader.config.SetSorter;
import uk.co.haradan.util.HttpUtils;

public class OCTGNImageLoader {
	
	private OCTGNImageLoaderConfig octgnPluginConfig = OCTGNImageLoaderConfig.NETRUNNER_CONFIG;
	
	private boolean isActive = false;
	private boolean isAbort = false;
	
	private AbortListener abortListener;
	
	public static interface AbortListener {
		public boolean isAbort();
	}
	
	private synchronized void activate() throws InterruptedException {
		if(isActive) {
			isAbort = true;
			wait();
		}
		isActive = true;
		isAbort = false;
	}
	
	private synchronized boolean deactivate() {
		if(! isActive) return false;
		isActive = false;
		notify();
		return true;
	}
	
	private synchronized void checkAbort() throws AbortException {
		if(isAbort
		|| (abortListener != null && abortListener.isAbort())) {
			throw new AbortException();
		}
	}
	
	private static class AbortException extends Exception {
		private static final long serialVersionUID = 5965018251907378579L;
	}
	
	public synchronized OCTGNImageLoaderConfig getOctgnPluginConfig() {
		return octgnPluginConfig;
	}
	public synchronized void setOctgnPluginConfig(OCTGNImageLoaderConfig octgnPluginConfig) {
		this.octgnPluginConfig = octgnPluginConfig;
	}
	
	public List<Set> loadSets(LogOutput log, File octgnDir) {
		try {
			return readSets(log, octgnDir);
		} catch (Exception e) {
			log.error(e);
			return null;
		}
	}
	
	private List<Set> readSets(LogOutput log, File octgnDir) throws ParserConfigurationException, SAXException {
		
		if(! octgnDir.isDirectory()) {
			log.errorln("Specified OCTGN data directory does not exist, please ensure it is correct or install OCTGN.");
			return null;
		}
		
		String pluginId;
		String pluginName;
		OctgnCardKeyBuilder<?> cardKeyBuilder;
		SetSorter sorter;
		synchronized(this) {
			CardKeyBuilderConfig<?> keyBuilderConf = octgnPluginConfig.getCardKeyBuilderConfig();
			cardKeyBuilder = keyBuilderConf.getOctgnKeyBuilder();
			pluginId = octgnPluginConfig.getPluginId();
			pluginName = octgnPluginConfig.getPluginName();
			sorter = octgnPluginConfig.getSetSorter();
		}
		
		File gameDbDir = new File(octgnDir, "GameDatabase/"+pluginId);
		if(! gameDbDir.isDirectory()) {
			log.errorln("Could not find OCTGN "+pluginName+" plugin directory, is it installed?");
			return null;
		}
		
		File gameDbSetsDir = new File(gameDbDir, "Sets");
		File[] setDirs = gameDbSetsDir.listFiles(new FileFilter() {
			@Override
			public boolean accept(File arg) {
				return arg.isDirectory();
			}
		});
		if(setDirs == null || setDirs.length < 1) {
			log.errorln("Could not find OCTGN "+pluginName+" sets, have cards been downloaded?");
			return null;
		}
		
		log.println("Loading sets for "+pluginName+" from OCTGN directory: "+gameDbDir.getAbsolutePath());

	    SAXParserFactory spf = SAXParserFactory.newInstance();
		SAXParser parser = spf.newSAXParser();
		
		List<Set> sets = new ArrayList<Set>();
		for(File setDir : setDirs) {
			File setXml = new File(setDir, "set.xml");
			OCTGNSetXmlHandler handler = new OCTGNSetXmlHandler(setDir.getName(), cardKeyBuilder);
		    try {
				parser.parse(setXml, handler);
			} catch (Exception e) {
				log.error(e);
				continue;
			}
		    sets.add(handler.getSet());
		}
		
		if(sorter != null) sorter.sort(sets);
		log.println("Found "+sets.size()+" sets");
		return sets;
	}
	
	public SetSelector getSetSelector() {
		if(octgnPluginConfig == null) return null;
		return octgnPluginConfig.getSetSelector();
	}

	public void downloadOctgnImages(LogOutput log, File octgnDir, List<Set> sets, AbortListener abortListener) {
		try {
			activate();
		} catch (InterruptedException e) {
			return;
		}
		this.abortListener = abortListener;
		try {
			doDownloadOctgnImages(log, octgnDir, sets);
		} catch (AbortException e) {
			log.errorln("Aborted");
		} finally {
			deactivate();
		}
	}
	
	private void doDownloadOctgnImages(LogOutput log, File octgnDir, List<Set> sets) throws AbortException {
		
		if(! octgnDir.isDirectory()) {
			log.errorln("Specified OCTGN data directory does not exist, please ensure it is correct or install OCTGN.");
			return;
		}
		
		CardKeyBuilderConfig<?> keyBuilderConf;
		String cardsUrl;
		
		synchronized(this) {
			keyBuilderConf = octgnPluginConfig.getCardKeyBuilderConfig();
			cardsUrl = octgnPluginConfig.getCardsUrl();
		}
		
		log.println("Using cards feed: "+cardsUrl);
		
		Map<CardKey, WebsiteCard> cardsByNum;
		try {
			cardsByNum = readCardsByKeys(cardsUrl, keyBuilderConf.getWebsiteKeyBuilder());
			
			downloadImages(log, octgnDir, cardsByNum, sets);
		} catch(AbortException e) {
			throw e;
		} catch (Exception e) {
			log.error(e);
			return;
		}
		
		log.println("Complete");
	}
	
	private static Map<CardKey, WebsiteCard> readCardsByKeys(String url, JsonCardKeyBuilder<?> builder) throws JsonParseException, IOException, KeyManagementException, NoSuchAlgorithmException {
		URLConnection conn = HttpUtils.getConnection(url);
		InputStream is = conn.getInputStream();
		try {
			JsonFactory jsonFactory = new JsonFactory();
			BufferedReader reader = new BufferedReader(new InputStreamReader(is));
			JsonParser parser;
			parser = jsonFactory.createParser(reader);
//			String json = IOUtils.toString(is);
//			parser = jsonFactory.createParser(json);

			try {
				return readCardsByKeys(parser, builder);
			} catch(JsonParseException e) {
				throw e;
			}
			
		} finally {
			is.close();
		}
	}
	
	private static Map<CardKey, WebsiteCard> readCardsByKeys(JsonParser parser, JsonCardKeyBuilder<?> keyBuilder) throws JsonParseException, IOException {
		
		JsonToken token = parser.nextToken();
		while(token != JsonToken.START_ARRAY) {
			if(token == null) return null;
			token = parser.nextToken();
		}
		
		Map<CardKey, WebsiteCard> cardsByKey = new HashMap<CardKey, WebsiteCard>();

		WebsiteCard card = null;
		while((token = parser.nextToken()) != null) {
			
			if(token == JsonToken.START_OBJECT) {
				card = new WebsiteCard();
				keyBuilder.startCard();
				
			} else if(token == JsonToken.FIELD_NAME) {
				String fieldName = parser.getCurrentName();
				JsonToken valueToken = parser.nextToken();
				
				keyBuilder.readField(fieldName, valueToken, parser);
				
				if("title".equals(fieldName)) {
					String title = parser.getValueAsString();
					card.setTitle(title);
				} else if("imagesrc".equals(fieldName)) {
					String url = parser.getValueAsString();
					card.setImgPath(url);
				} else if("image_url".equals(fieldName)) {
					String url = parser.getValueAsString();
					card.setImgUrl(url);
				} else if("code".equals(fieldName)) {
					String code = parser.getValueAsString();
					card.setCode(code);
				}
				
			} else if(token == JsonToken.END_OBJECT) {

				keyBuilder.endCard();
				CardKey key = keyBuilder.buildKey();
				
				cardsByKey.put(key, card);
				
			} else if(token == JsonToken.END_ARRAY) {
				break;
			}
		}
		
		return cardsByKey;
	}
	
	private void downloadImages(LogOutput log, File octgnDir, Map<CardKey, WebsiteCard> cardsByKey, List<Set> sets) throws AbortException {
		
		String pluginId;
		String imageBaseUrl;
		String imageUrlTemplate;
		synchronized(this) {
			pluginId = octgnPluginConfig.getPluginId();
			imageBaseUrl = octgnPluginConfig.getImageBaseUrl();
			imageUrlTemplate = octgnPluginConfig.getImageUrlTemplate();
		}
		
		File imageSetsDir = new File(octgnDir, "ImageDatabase/"+pluginId+"/Sets");
		imageSetsDir.mkdirs();
		
		for(Set set : sets) {
			final String setId = set.getId();
			log.println("Downloading cards for set \""+set.getName()+"\" (OCTGN dir "+setId+")");
			
			File setImgDir = new File(imageSetsDir, setId+"/Cards");
			setImgDir.mkdirs();
			
			for(SetCard setCard : set.getCards()) {
				checkAbort();

				// Confirm this card can be downloaded
				final String cardId = setCard.getId();
				CardKey key = setCard.getCardKey();
				WebsiteCard card = cardsByKey.get(key);
				if(card == null) {
					log.println("Card not in website: \""+setCard.getName()+"\" ("+cardId+")");
					continue;
				}
				String imgUrl = getFullImgUrl(card, imageBaseUrl, imageUrlTemplate);
				if(imgUrl == null) {
					log.println("Card without image URL: \""+card.getTitle()+"\" ("+cardId+")");
					continue;
				}

				// Download new card file
				File downloadFile = new File(setImgDir, cardId+".png.tmp");
				try {
					URLConnection conn = HttpUtils.getConnection(imgUrl);
					conn.addRequestProperty("User-Agent", 
							"Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.0)");
					InputStream is = conn.getInputStream();
					try {
						FileOutputStream os = new FileOutputStream(downloadFile);
						try {
							IOUtils.copy(is, os);
						} finally {
							os.close();
						}
					} finally {
						is.close();
					}
				} catch (Exception e) {
					log.error(e);
					continue;
				}
				
				// Delete old card files
				File[] cardFiles = setImgDir.listFiles(new FileFilter() {
					@Override
					public boolean accept(File file) {
						if(!file.isFile()) return false;
						String name = file.getName();
						return name.startsWith(cardId) && ! name.endsWith(".tmp");
					}
				});
				for(File cardFile : cardFiles) {
					cardFile.delete();
				}

				// Install new card file
				File cardFile = new File(setImgDir, cardId+".png");
				downloadFile.renameTo(cardFile);
			}
		}
		
	}
	
	private String getFullImgUrl(WebsiteCard card, String imageBaseUrl, String imageUrlTemplate) {
		String url = card.getImgUrl();
		if(url != null) return url;
		String path = card.getImgPath();
		if(path != null && imageBaseUrl != null) {
			return imageBaseUrl+path;
		}
		String code = card.getCode();
		if(code != null && imageUrlTemplate != null) {
			return imageUrlTemplate.replace("{code}", code);
		}
		return null;
	}

}
