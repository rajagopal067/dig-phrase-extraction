package phrase_extractor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import org.ahocorasick.trie.Token;
import org.ahocorasick.trie.Trie;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.broadcast.Broadcast;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;


public class ElectronicsExctractor {

	protected static final String JSONObject = null;

	public static void main(final String[] args) throws IOException, ClassNotFoundException, ParseException {
		String inputFile = args[0];
		String type = args[1];
		String keywordsFile = args[2];
		String outputFile = args[3];

		SparkConf conf = new SparkConf().setAppName("findKeywords").setMaster("local").registerKryoClasses(new Class<?>[]{
				Class.forName("org.apache.hadoop.io.LongWritable"),
				Class.forName("org.apache.hadoop.io.Text")
		});;
//		conf.set("spark.kryoserializer.buffer.mb","128");
		JavaSparkContext sc = new JavaSparkContext(conf);
		JavaRDD<String> jsonRDD = null;
		JavaPairRDD<Text, Text> sequenceRDD = null;
		JavaRDD<String> keywordsRDD = sc.textFile(keywordsFile);
		final String wordsListJson = keywordsRDD.first();
		
		
		
		JSONParser parser = new JSONParser();
		JSONObject keywordsJsonArray = (JSONObject) parser.parse(wordsListJson);
		final Trie trie = getTrie(wordsListJson);
		final JSONObject misspellings = (JSONObject) keywordsJsonArray.get("misspellings");
		JSONArray wordsList = (JSONArray) keywordsJsonArray.get("wordsList");
		
		final Broadcast<Trie> broadcastTrie = sc.broadcast(trie);
		final Broadcast<JSONArray> broadcastWordsList = sc.broadcast(wordsList);
		final Broadcast<org.json.simple.JSONObject> broadcastMisspellings = sc.broadcast(misspellings);

//		final File[] inputFiles = new File(inputFileDir).listFiles();
//		for (File inputFile : inputFiles) {
			if (type.equals("json")) {
				jsonRDD = sc.textFile(inputFile);
				JavaRDD<String> words = jsonRDD.flatMap(new FlatMapFunction<String, String>() {
							@Override
							public Iterable<String> call(String x)
									throws Exception {
								JSONParser parser = new JSONParser();
								JSONArray offersJsonArray = (JSONArray) parser
										.parse(x);
								for (int i = 0; i < offersJsonArray.size(); i++) {
									JSONObject row = (JSONObject) offersJsonArray
											.get(i);
									String rawText = (String) row
											.get("raw_text");
									HashMap<String, HashSet<String>> keywordsExtracted = extractKeywords(rawText, wordsListJson);
									row.put("wordlists", keywordsExtracted);
								}
								return Arrays.asList(offersJsonArray.toJSONString());
							}
						});
				//		FileUtils.deleteDirectory(new File(outputFile));	
				words.saveAsTextFile(outputFile);

			} else {
				sequenceRDD = sc.sequenceFile(inputFile, Text.class, Text.class);

				JavaPairRDD<Text, Text> words = sequenceRDD.flatMapValues(new Function<Text, Iterable<Text>>() {
							@Override
							public Iterable<Text> call(Text json) throws Exception {

								JSONParser parser = new JSONParser();
								JSONObject row = (JSONObject) parser.parse(json.toString());
								String rawText = (String) ((JSONObject) row
										.get("_source")).get("raw_text");
								rawText = rawText.replaceAll("(?m)^[ \t]*\r?\n", "");
								System.out.println(rawText);
								HashSet<String> keywordsContainedList = new HashSet<String>();
								HashMap<String, HashSet<String>> keywordsMap = new HashMap<String, HashSet<String>>();
								JSONArray wordsList = broadcastWordsList.getValue();
								
									if(rawText != null){
										Collection<Token> tokens = broadcastTrie.getValue().tokenize(rawText.toLowerCase());
										for (Token token : tokens) {
											if (token.isMatch()) {
												// takes the correct from misspellings mapping the json file
												String correct_word = (String) broadcastMisspellings.getValue().get(token.getFragment().toLowerCase());
												keywordsContainedList.add("\""+ correct_word + "\"");
											}
										}
										System.out.println(keywordsContainedList);
//										keywordsMap.put((String) obj.get("name"),keywordsContainedList);
									}
									row.put("wordslists", keywordsContainedList);		
								
								return Arrays.asList(new Text(row.toJSONString()));
							}

						});
				//			FileUtils.deleteDirectory(new File(outputFile));
				words.saveAsTextFile(outputFile);
//							words.saveAsNewAPIHadoopFile(outputFile, Text.class, Text.class, SequenceFileOutputFormat.class);
			}
//		}
	}
	
	
	
	private static Trie getTrie(String wordsListJson) throws ParseException{
		
		JSONParser parser = new JSONParser();
		JSONObject keywordsJsonArray = (JSONObject) parser.parse(wordsListJson);
		JSONArray wordsList = (JSONArray) keywordsJsonArray.get("wordsList");
		Trie trie = new Trie().removeOverlaps().onlyWholeWords().caseInsensitive();
		for (int j = 0; j < wordsList.size(); j++) {
			trie.addKeyword(wordsList.get(j).toString());

		}
		
		return trie;

	}
	

	// this extracts the keywords which are present in all the 5 files from the raw_text
	private static HashMap<String, HashSet<String>> extractKeywords(String rawText,String wordsListJson) throws ParseException{
		JSONParser parser = new JSONParser();
		JSONObject keywordsJsonArray = (JSONObject) parser.parse(wordsListJson);
		JSONArray wordsList = (JSONArray) keywordsJsonArray.get("wordsList");
		JSONObject misspellings = (JSONObject) keywordsJsonArray.get("misspellings");

		HashMap<String, HashSet<String>> keywordsMap = new HashMap<String, HashSet<String>>();
		for (int j = 0; j < wordsList.size(); j++) {

			Trie trie = new Trie().removeOverlaps().onlyWholeWords().caseInsensitive();
			HashSet<String> keywordsContainedList = new HashSet<String>();
			JSONObject obj = (JSONObject) wordsList.get(j);
			ArrayList<String> wordList = (ArrayList<String>) obj.get("words");
			for (String word : wordList) {
				trie.addKeyword(word.toLowerCase());
			}
			Collection<Token> tokens = trie.tokenize(rawText.toLowerCase());
			for (Token token : tokens) {
				if (token.isMatch()) {
					// takes the correct from misspellings mapping the json file
					String correct_word = (String) misspellings.get(token.getFragment().toLowerCase());
					keywordsContainedList.add("\""+correct_word+"\"");
				}
			}
			keywordsMap.put((String) obj.get("name"),keywordsContainedList);

		}

		return keywordsMap;
	}


	

}
