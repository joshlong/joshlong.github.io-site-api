package com.joshlong.index;

import com.joshlong.*;
import com.joshlong.lucene.DocumentWriteMapper;
import com.joshlong.lucene.LuceneTemplate;
import org.apache.lucene.document.*;
import org.apache.lucene.index.Term;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.util.Assert;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.text.DateFormat;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

class DefaultIndexService implements IndexService {

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final DateFormat simpleDateFormat;

	private final File root;

	private final Map<String, BlogPost> index = new ConcurrentHashMap<>();

	private final ApplicationEventPublisher publisher;

	private final BlogPostService blogPostService;

	private final Object monitor = new Object();

	private final LuceneTemplate luceneTemplate;

	private final URI gitRepository;

	private final boolean resetOnRebuild;

	private final Set<String> extensions = Arrays.stream(BlogPostContentType.values())//
			.map(contentType -> contentType.name().toLowerCase(Locale.ROOT))//
			.collect(Collectors.toSet());

	DefaultIndexService(DateFormat simpleDateFormat, ApplicationEventPublisher publisher,
			BlogPostService blogPostService, LuceneTemplate luceneTemplate, URI gitRepository, File contentRoot,
			boolean resetOnRebuild) {
		this.simpleDateFormat = simpleDateFormat;
		this.blogPostService = blogPostService;
		this.resetOnRebuild = resetOnRebuild;
		this.luceneTemplate = luceneTemplate;
		this.root = contentRoot;
		this.publisher = publisher;
		this.gitRepository = gitRepository;
	}

	private void ensureClonedRepository() throws GitAPIException {

		log.info("should reset Git clone? {}", this.resetOnRebuild);

		if (!this.resetOnRebuild)
			return;

		if (this.root.exists() && this.root.isDirectory()) {
			log.info("deleting {}.", this.root.getAbsolutePath());
			FileSystemUtils.deleteRecursively(this.root);
		}

		var repo = Git.cloneRepository().setDirectory(this.root).setURI(this.gitRepository.toString()).call()
				.getRepository();

		try (var git = new Git(repo)) {
			var status = git.status().call();
			this.log.info("the status is {}", status.toString());
		}
	}

	@Override
	public IndexRebuildStatus rebuildIndex() throws Exception {
		this.log.info("refreshing {}", IndexService.class.getName());
		Assert.notNull(this.root, () -> "you must specify a valid root ");
		this.publisher.publishEvent(new IndexingStartedEvent(new Date()));
		this.ensureClonedRepository();
		Assert.state(this.root.exists() && Objects.requireNonNull(this.root.list()).length > 0,
				() -> "there's no cloned repository under the root " + this.root.getAbsolutePath() + '.');
		synchronized (this.monitor) {
			this.index.clear();
			this.index.putAll(this.buildIndex());
		}
		var now = new Date();
		Assert.state(!this.index.isEmpty(), () -> "there are no entries in the content index. Something's wrong! "
				+ "Ensure you have content registered.");
		this.publisher.publishEvent(new IndexingFinishedEvent(this.index, now));
		return new IndexRebuildStatus(this.index.size(), now);

	}

	@Override
	public Map<String, BlogPost> getIndex() {
		return this.index;
	}

	@Override
	public BlogPostSearchResults search(String query, int offset, int pageSize, boolean listedOnly) {
		var results = this.searchIndex(query, this.index.size()) //
				.stream() //
				.map(this.index::get) //
				.sorted(Comparator.comparing(BlogPost::date).reversed()) //
				.filter(bp -> listedOnly && bp.listed()).toList();
		var returningList = results.subList(offset, Math.min(results.size(), offset + pageSize));
		log.info("search('{}',{},{},{})", query, offset, pageSize, listedOnly);
		return new BlogPostSearchResults(results.size(), offset, pageSize, returningList);
	}

	private List<String> searchIndex(String queryStr, int maxResults) {
		var list = this.luceneTemplate.search(queryStr, maxResults, document -> document.get("path"));
		log.info("the result is {}", list.size());
		return list;
	}

	private String computePath(File file, File contentDirectory) {
		var ext = ".md";
		var sub = file.getAbsolutePath().substring(contentDirectory.getAbsolutePath().length());
		if (sub.endsWith(ext))
			sub = sub.substring(0, sub.length() - ext.length()) + ".html";
		return sub.toLowerCase(Locale.ROOT);
	}

	private Map<String, BlogPost> buildIndex() throws IOException {
		log.debug("building index @ {}.", Instant.now());
		var contentDirectory = new File(this.root, "content");
		var mapOfContent = new ConcurrentHashMap<String, BlogPost>();
		var executor = Executors.newVirtualThreadPerTaskExecutor();
		var files = new ArrayList<CompletableFuture<?>>();
		for (var path : Files.walk(contentDirectory.toPath()).collect(Collectors.toSet())) {
			var file = path.toFile();
			if (this.isValidFile(file)) {
				files.add(CompletableFuture.runAsync(() -> {
					var blogPost = blogPostService.buildBlogPostFrom(computePath(file, contentDirectory), file);
					mapOfContent.put(blogPost.path(), blogPost);
				}, executor));
			}
		}
		CompletableFuture.allOf(files.toArray(new CompletableFuture[0])).join();
		this.log.info("ran the index for all the files of size {}", mapOfContent.size());
		this.luceneTemplate.write(mapOfContent.entrySet(), entry -> {
			var path = entry.getKey();
			var blogPost = entry.getValue();
			var doc = buildBlogPost(path, blogPost);
			return new DocumentWriteMapper.DocumentWrite(new Term("key", buildHashKeyFor(blogPost)), doc);
		});
		return mapOfContent;
	}

	private String buildHashKeyFor(BlogPost blogPost) {
		Assert.notNull(blogPost, () -> "the blog must not be null");
		Assert.notNull(blogPost.date(), () -> "the blog date must not be null");
		Assert.notNull(blogPost.title(), () -> "the blog title must not be null");
		var title = blogPost.title();
		var stringBuilder = new StringBuilder();
		for (var c : title.toCharArray()) {
			if (Character.isAlphabetic(c)) {
				stringBuilder.append(c);
			}
		}
		return stringBuilder + this.simpleDateFormat.format(blogPost.date());
	}

	private String htmlToText(String htmlMarkup) {
		return Jsoup.parse(htmlMarkup).text();
	}

	private Document buildBlogPost(String relativePath, BlogPost post) {
		var document = new Document();
		document.add(new TextField("title", post.title(), Field.Store.YES));
		document.add(new TextField("path", relativePath, Field.Store.YES));
		document.add(new TextField("originalContent", post.originalContent(), Field.Store.YES));
		document.add(new TextField("content", htmlToText(post.processedContent()), Field.Store.YES));
		document.add(new LongPoint("time", post.date().getTime()));
		document.add(new StringField("key", buildHashKeyFor(post), Field.Store.YES));
		return document;
	}

	private boolean isValidFile(File fileName) {
		var lcFn = fileName.getName().toLowerCase(Locale.ROOT);
		for (var e : this.extensions)
			if (lcFn.contains(e))
				return true;
		return false;
	}

	@EventListener(ApplicationReadyEvent.class)
	void onApplicationReadyEvent() throws Exception {
		rebuildIndex();
	}

	@EventListener(SiteUpdatedEvent.class)
	void onSiteReadyEvent() throws Exception {
		rebuildIndex();
	}

}