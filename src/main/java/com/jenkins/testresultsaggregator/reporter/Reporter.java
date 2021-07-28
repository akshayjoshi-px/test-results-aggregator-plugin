package com.jenkins.testresultsaggregator.reporter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.google.common.base.Strings;
import com.jenkins.testresultsaggregator.TestResultsAggregator.AggregatorProperties;
import com.jenkins.testresultsaggregator.data.Aggregated;
import com.jenkins.testresultsaggregator.data.Data;
import com.jenkins.testresultsaggregator.data.ImagesMap;
import com.jenkins.testresultsaggregator.data.ImagesMap.ImageData;
import com.jenkins.testresultsaggregator.data.Job;
import com.jenkins.testresultsaggregator.data.JobStatus;
import com.jenkins.testresultsaggregator.helper.LocalMessages;

import hudson.FilePath;

public class Reporter {
	
	private PrintStream logger;
	private FilePath workspace;
	private File rootDir;
	private String mailNotificationFrom;
	private boolean ignoreNotFoundJobs;
	private boolean ignoreDisabledJobs;
	private boolean ignoreAbortedJobs;
	private boolean foundAtLeastOneGroupName;
	
	public Reporter(PrintStream logger, FilePath workspace, File rootDir, String mailNotificationFrom, Boolean ignoreDisabledJobs, Boolean ignoreNotFoundJobs, Boolean ignoreAbortedJobs) {
		this.logger = logger;
		this.workspace = workspace;
		this.rootDir = rootDir;
		this.mailNotificationFrom = mailNotificationFrom;
		this.ignoreDisabledJobs = ignoreDisabledJobs;
		this.ignoreNotFoundJobs = ignoreNotFoundJobs;
		this.ignoreAbortedJobs = ignoreAbortedJobs;
	}
	
	public void publishResuts(Aggregated aggregated, Properties properties, List<LocalMessages> columns, File rootDirectory) throws Exception {
		List<Data> dataJob = aggregated.getData();
		foundAtLeastOneGroupName = false;
		for (Data data : dataJob) {
			if (!Strings.isNullOrEmpty(data.getGroupName())) {
				foundAtLeastOneGroupName = true;
				break;
			}
		}
		// Generate XML Report
		new XMLReporter(logger, rootDir).generateXMLReport(aggregated);
		// Calculate and Generate Columns
		if (!foundAtLeastOneGroupName) {
			columns.remove(LocalMessages.COLUMN_GROUP);
		}
		Aggregated aggregatedCopy = aggregated.copy();
		if (ignoreDisabledJobs) {
			ignoreJobsFromReport(aggregatedCopy.getData(), JobStatus.DISABLED);
		}
		if (ignoreNotFoundJobs) {
			ignoreJobsFromReport(aggregatedCopy.getData(), JobStatus.NOT_FOUND);
		}
		if (ignoreAbortedJobs) {
			ignoreJobsFromReport(aggregatedCopy.getData(), JobStatus.ABORTED);
		}
		// Generate HTML Report
		FilePath htmlReport = new HTMLReporter(logger, workspace).createOverview(aggregatedCopy, columns, properties.getProperty(AggregatorProperties.THEME.name()), foundAtLeastOneGroupName);
		// Generate Body message
		String bodyText = generateMailBody(htmlReport.read());
		// Calculate attachments
		Map<String, ImageData> images = resolveImages(bodyText);
		// Generate and Send Mail report
		new MailNotification(logger, aggregatedCopy.getData(), workspace, rootDirectory).send(
				properties.getProperty(AggregatorProperties.RECIPIENTS_LIST.name()),
				mailNotificationFrom,
				generateMailSubject(properties.getProperty(AggregatorProperties.SUBJECT_PREFIX.name()), aggregatedCopy),
				bodyText,
				images,
				properties.getProperty(AggregatorProperties.TEXT_BEFORE_MAIL_BODY.name()),
				properties.getProperty(AggregatorProperties.TEXT_AFTER_MAIL_BODY.name()));
	}
	
	private void ignoreJobsFromReport(List<Data> list, JobStatus status) {
		for (Data tempData : list) {
			List<Job> tempList = new ArrayList<>();
			for (Job temp : tempData.getJobs()) {
				if (temp.getResults() != null && !status.name().equalsIgnoreCase(temp.getResults().getStatus())) {
					tempList.add(temp);
				}
			}
			tempData.setJobs(tempList);
		}
	}
	
	private String generateMailBody(InputStream inputStream) throws Exception {
		BufferedReader buf = new BufferedReader(new InputStreamReader(inputStream, Charset.forName("UTF-8")));
		String line = buf.readLine();
		StringBuilder sb = new StringBuilder();
		while (line != null) {
			sb.append(line).append("\n");
			line = buf.readLine();
		}
		buf.close();
		String body = sb.toString();
		// Fix Images
		Set<String> setImageID = ImagesMap.getImages().keySet();
		for (String contentId : setImageID) {
			body = body.replaceAll(ImagesMap.getImages().get(contentId).getFileName(), ImagesMap.getImages().get(contentId).getCid());
		}
		return body;
	}
	
	private Map<String, ImageData> resolveImages(String bodyText) {
		Map<String, ImageData> images = new HashMap<>();
		Set<String> setImageID = ImagesMap.getImages().keySet();
		for (String contentId : setImageID) {
			if (bodyText.contains(ImagesMap.getImages().get(contentId).getCid())) {
				images.put(contentId, ImagesMap.getImages().get(contentId));
			}
		}
		return images;
	}
	
	private String generateMailSubject(String subjectPrefix, Aggregated aggregated) {
		String subject = subjectPrefix;
		if (aggregated.getRunningJobs() > 0) {
			subject += "||" + LocalMessages.RESULTS_RUNNING.toString() + " : " + aggregated.getRunningJobs();
		}
		if (aggregated.getSuccessJobs() > 0 || aggregated.getFixedJobs() > 0) {
			subject += "||" + LocalMessages.RESULTS_SUCCESS.toString() + " : " + (aggregated.getSuccessJobs() + aggregated.getFixedJobs());
		}
		if (aggregated.getFailedJobs() > 0 || aggregated.getKeepFailJobs() > 0) {
			subject += "||" + LocalMessages.RESULTS_FAILED.toString() + " : " + (aggregated.getFailedJobs() + aggregated.getKeepFailJobs());
		}
		if (aggregated.getUnstableJobs() > 0 || aggregated.getKeepUnstableJobs() > 0) {
			subject += "||" + LocalMessages.RESULTS_UNSTABLE.toString() + " : " + (aggregated.getUnstableJobs() + aggregated.getKeepUnstableJobs());
		}
		if (aggregated.getAbortedJobs() > 0) {
			subject += "||" + LocalMessages.RESULTS_ABORTED.toString() + " : " + aggregated.getAbortedJobs();
		}
		return subject;
	}
	
}
