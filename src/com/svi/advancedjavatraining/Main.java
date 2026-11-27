package com.svi.advancedjavatraining;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.svi.advancedjavatraining.object.City;
import com.svi.advancedjavatraining.object.CityInfo;
import com.svi.advancedjavatraining.object.PopulationData;
import com.svi.advancedjavatraining.object.Province;
import com.svi.advancedjavatraining.utils.JSONFileReader;
import com.svi.advancedjavatraining.utils.PopulationFileWriter;
import com.svi.advancedjavatraining.utils.WebLoader;

public class Main {

	private static final int POOL_SIZE = 100;
	private static final String OUTPUT_FOLDER = "Output";

	public static void main(String[] args) {
		long startTime = System.nanoTime();

		ExecutorService executor = Executors.newFixedThreadPool(POOL_SIZE);
		try {
			loadAndWritePopulation(executor);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			System.err.println("Loading was interrupted: " + e.getMessage());
		} catch (ExecutionException | IOException e) {
			System.err.println("Failed to load or write the population data: " + e);
		} finally {
			executor.shutdown();
		}

		long totalTime = System.nanoTime() - startTime;
		System.out.println(formatElapsedTime(totalTime));
	}

	private static void loadAndWritePopulation(ExecutorService executor)
			throws InterruptedException, ExecutionException, IOException {
		Future<List<Province>> provinceFuture = executor.submit(() -> new WebLoader().getProvinces());
		Future<List<City>> cityFuture = executor.submit(() -> new WebLoader().getCities());
		Future<PopulationFileWriter> writerFuture = executor.submit(Main::createWarmedUpWriter);

		Map<String, String> provinceNamesByKey = new LinkedHashMap<>();
		for (Province province : provinceFuture.get()) {
			provinceNamesByKey.put(province.getKey(), province.getName());
		}

		Map<String, List<City>> citiesByProvinceKey = new HashMap<>();
		for (City city : cityFuture.get()) {
			citiesByProvinceKey.computeIfAbsent(city.getProvince(), key -> new ArrayList<>()).add(city);
		}

		List<String> provinceNames = new ArrayList<>();
		List<City> cities = new ArrayList<>();
		List<Future<CityInfo>> cityInfoFutures = new ArrayList<>();
		for (Map.Entry<String, String> provinceEntry : provinceNamesByKey.entrySet()) {
			String provinceName = provinceEntry.getValue();
			List<City> provinceCities = citiesByProvinceKey.remove(provinceEntry.getKey());
			if (provinceCities == null) {
				continue;
			}
			for (City city : provinceCities) {
				Callable<CityInfo> readTask = () -> new JSONFileReader(provinceName, city.getName()).getData();
				provinceNames.add(provinceName);
				cities.add(city);
				cityInfoFutures.add(executor.submit(readTask));
			}
		}
		for (Map.Entry<String, List<City>> unmatched : citiesByProvinceKey.entrySet()) {
			System.err.println("No province found for " + unmatched.getValue().size()
					+ " city(ies) with key " + unmatched.getKey());
		}

		PopulationFileWriter writer = writerFuture.get();
		double totalPopulation = 0;
		for (int i = 0; i < cities.size(); i++) {
			CityInfo cityInfo = cityInfoFutures.get(i).get();
			double population = (cityInfo == null) ? 0 : cityInfo.getPopulation();
			totalPopulation += population;
			writer.addPopulationRecord(new PopulationData(provinceNames.get(i), cities.get(i).getName(), population));
		}

		Files.createDirectories(Paths.get(OUTPUT_FOLDER));
		writer.writeToFile();

		System.out.println("Total cities loaded: " + cities.size());
		System.out.println("Total population: " + NumberFormat.getNumberInstance(Locale.US).format(totalPopulation));
	}

	private static PopulationFileWriter createWarmedUpWriter() throws IOException {
		try (Workbook warmUp = new XSSFWorkbook()) {
			Sheet sheet = warmUp.createSheet("WarmUp");
			Row row = sheet.createRow(0);
			row.createCell(0).setCellValue("Province");
			row.createCell(1).setCellValue(0);
			warmUp.write(new ByteArrayOutputStream());
		}
		return new PopulationFileWriter();
	}

	private static String formatElapsedTime(long nanos) {
		long hours = TimeUnit.NANOSECONDS.toHours(nanos);
		long minutes = TimeUnit.NANOSECONDS.toMinutes(nanos) % 60;
		long seconds = TimeUnit.NANOSECONDS.toSeconds(nanos) % 60;
		long millis = TimeUnit.NANOSECONDS.toMillis(nanos) % 1000;
		return String.format("%d:%02d:%02d.%03d", hours, minutes, seconds, millis);
	}

}
