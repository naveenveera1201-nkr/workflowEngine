package com;

import com.configs.ConfigLoader;
import com.configs.ConfigType;
import com.configs.registries.process.InMemoryProcessRegistry;
import com.configs.registries.process.ProcessRegistry;
import com.configs.registries.status.InMemoryStatusRegistry;
import com.configs.registries.status.StatusRegistry;
import com.exceptions.OptimisticLockException;
import com.exceptions.ProcessFlowException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.models.*;
import com.models.Process;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ProcessQueryBuilder;
import com.mongodb.client.*;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

public class ProcessFlowEngine implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ProcessFlowEngine.class);

    private MongoClient mongoClient;
    private MongoDatabase database;
    private final ObjectMapper objectMapper;
    private final ProcessFlowConfig config;
    private final StatusRegistry statusRegistry;
    private final ProcessRegistry processRegistry;

    public ProcessFlowEngine() throws ProcessFlowException {
        this(ProcessFlowConfig.builder().build());
    }

    public ProcessFlowEngine(ProcessFlowConfig config) throws ProcessFlowException {
        this.config = config;
        this.objectMapper = JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();


        this.statusRegistry = new InMemoryStatusRegistry();
        this.processRegistry = new InMemoryProcessRegistry();
        configMongoDB(config);
        loadConfig(config);
    }

    private void configMongoDB(ProcessFlowConfig config) throws ProcessFlowException {
        // Configure MongoDB client with connection pooling
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new com.mongodb.ConnectionString(config.getConnectionString()))
                .applyToConnectionPoolSettings(builder -> builder
                        .maxSize(config.getConnectionPoolSize())
                        .maxWaitTime(config.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS))
                .applyToSocketSettings(builder -> builder
                        .connectTimeout(config.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS)
                        .readTimeout(config.getSocketTimeoutMs(), TimeUnit.MILLISECONDS))
                .retryWrites(true)
                .retryReads(true)
                .build();

        MongoClient client = MongoClients.create(settings);

        CodecRegistry pojoCodecRegistry =
                fromRegistries(
                        MongoClientSettings.getDefaultCodecRegistry(),
                        fromProviders(PojoCodecProvider.builder().automatic(true).build())
                );
        MongoDatabase mongoDatabase = client
                .getDatabase(config.getDatabaseName())
                .withCodecRegistry(pojoCodecRegistry);
        validateMongoDBConnection(client, mongoDatabase);

        this.mongoClient = client;
        this.database = mongoDatabase;
        logger.info("[ProcessFlowService] Database configuration completed :: database-name : {}", config.getDatabaseName());
    }

    private void validateMongoDBConnection(MongoClient client, MongoDatabase mongoDatabase) throws ProcessFlowException {
        if (client == null || mongoDatabase == null) {
            throw new ProcessFlowException("MongoDB client or database not initialized");
        }

        try {
            // Official MongoDB health check
            mongoDatabase.runCommand(new BsonDocument("ping", new BsonInt32(1)));
        } catch (MongoTimeoutException e) {
            throw new ProcessFlowException("MongoDB connection timeout", e);
        } catch (MongoException e) {
            throw new ProcessFlowException("MongoDB connection validation failed", e);
        }
    }

    private void loadConfig(ProcessFlowConfig config) throws ProcessFlowException {
        long start = System.currentTimeMillis();
        try {
            ConfigLoader configLoader = ConfigLoader.defaultConfigLoader(objectMapper, processRegistry, statusRegistry);
            Map<ConfigType, String> externalConfigPaths = new HashMap<>();
            externalConfigPaths.put(ConfigType.STATUS, config.getStatusConfigPath());
            externalConfigPaths.put(ConfigType.PROCESS, config.getProcessConfigPath());
            configLoader.loadAll(externalConfigPaths);
            logger.info("[ProcessFlowService] Configuration registries loaded successfully in {} ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            logger.error("Failed to load configuration registries", e);
            throw new ProcessFlowException("Configuration loading failed", e);
        }
    }

    public String createProcessInstance(String processCode, String createdBy) throws ProcessFlowException {
        return createProcessInstance(processCode, createdBy, null);
    }

    public String createProcessInstance(String processCode, String createdBy, Map<String, Object> data) throws ProcessFlowException {
        if (CommonUtils.isBlank(processCode)) {
            throw new ProcessFlowException("Process code must be non-empty");
        }
        if (CommonUtils.isBlank(createdBy)) {
            throw new ProcessFlowException("CreatedBy must be non-empty");
        }


        Process process = processRegistry.get(processCode);

        ProcessInstance instance = new ProcessInstance();
        instance.setId(new ObjectId());
        instance.setProcessCode(processCode);
        instance.setCurrentStatus(process.getInitialStatus());
        instance.setCurrentLevel(Boolean.TRUE.equals(process.getApprovalFlow()) ? 1 : 0);
        instance.setCreatedBy(createdBy);
        instance.setModifiedBy(createdBy);
        instance.setData(data);
        instance.setHistory(new ArrayList<>());

        // Add initial history entry
        ProcessHistory history = new ProcessHistory();
        history.setLevel(0);
        history.setAction(ProcessAction.CREATED.toString());
        history.setFromStatus(null);
        history.setToStatus(process.getInitialStatus());
        history.setPerformedBy(createdBy);
        history.setPerformedDate(Instant.now());
        instance.getHistory().add(history);

        // Retry mechanism for transient failures
        return executeWithRetry(() -> {
            MongoCollection<ProcessInstance> collection = database.getCollection("process_instances", ProcessInstance.class);
            collection.insertOne(instance);
            logger.info("Created process instance {}", instance.getId());
            return instance.getId().toHexString();
        }, "create process instance");
    }

    public ProcessInstance performAction(String instanceId, String action, String performedBy, String comments) throws ProcessFlowException {
        if (CommonUtils.isBlank(instanceId)) {
            throw new ProcessFlowException("Instance ID code must be non-empty");
        }
        if (CommonUtils.isBlank(action)) {
            throw new ProcessFlowException("Action cannot be null");
        }
        if (CommonUtils.isBlank(performedBy)) {
            throw new ProcessFlowException("Performed by must be non-empty");
        }

        return executeWithRetry(() -> {
            MongoCollection<ProcessInstance> collection = database.getCollection("process_instances", ProcessInstance.class);

            // Use optimistic locking if enabled
            ProcessInstance instance;
            Integer originalVersion = null;

            instance = getProcessInstance(instanceId);
            if(isProcessComplete(instance)) {
                throw new ProcessFlowException("Invalid operation: process already completed");
            }

            if (config.isEnableOptimisticLocking()) {
                originalVersion = instance.getVersion();
            }

            Process process = processRegistry.get(instance.getProcessCode());
            if (process == null || !Boolean.TRUE.equals(process.getApprovalFlow())) {
                throw new ProcessFlowException("Process does not support approval flow");
            }

            // Find the applicable approval step
            ApprovalStep step = process.getApprovalSteps()
                    .stream()
                    .filter(s -> s.getAction().equalsIgnoreCase(action) && s.getPrevStatus().equals(instance.getCurrentStatus()))
                    .findFirst()
                    .orElseThrow(() -> new ProcessFlowException(String.format("Invalid action '%s' for status %d at level %d", action, instance.getCurrentStatus(), instance.getCurrentLevel())));

            // Calculate duration in previous status
            long durationMs = 0;
            if (!instance.getHistory().isEmpty()) {
                ProcessHistory lastHistory = instance.getHistory().get(instance.getHistory().size() - 1);
                durationMs = Duration.between(lastHistory.getPerformedDate(), Instant.now()).toMillis();
            }

            // Update instance
            Integer oldStatus = instance.getCurrentStatus();
            Integer oldLevel = instance.getCurrentLevel();
            instance.setCurrentStatus(step.getNextStatus());

            // Update level
            instance.setCurrentLevel(step.getLevel());

            instance.setModifiedDate(Instant.now());
            instance.setModifiedBy(performedBy);
            instance.setVersion(instance.getVersion() + 1);

            // Add history entry
            ProcessHistory history = new ProcessHistory();
            history.setLevel(oldLevel);
            history.setAction(action);
            history.setFromStatus(oldStatus);
            history.setToStatus(step.getNextStatus());
            history.setPerformedBy(performedBy);
            history.setComments(comments);
            history.setDurationMs(durationMs);
            instance.getHistory().add(history);

            if(isProcessComplete(instance)) {
                moveToCompletedCollection(instance, originalVersion);
            } else if (config.isEnableOptimisticLocking()) {
                long updateCount = collection.replaceOne(and(eq("_id", new ObjectId(instanceId)), eq("version", originalVersion)), instance).getModifiedCount();
                if (updateCount == 0) {
                    throw new OptimisticLockException("Process instance was modified by another transaction. Please retry.");
                }
            } else {
                collection.replaceOne(eq("_id", new ObjectId(instanceId)), instance);
            }

            logger.info("Performed action '{}' on instance {} by {}", action, instanceId, performedBy);
            return instance;

        }, "perform action");
    }

    private void moveToCompletedCollection(ProcessInstance instance, Integer originalVersion)
            throws ProcessFlowException {

        MongoCollection<ProcessInstance> active =
                database.getCollection("process_instances", ProcessInstance.class);
        MongoCollection<ProcessInstance> completed =
                database.getCollection("process_instances_completed", ProcessInstance.class);

        long deleteCount = active.deleteOne(
                and(eq("_id", instance.getId()), eq("version", originalVersion))
        ).getDeletedCount();

        if (deleteCount == 0) {
            throw new OptimisticLockException("Process instance modified before completion");
        }

        completed.insertOne(instance);
    }

    public List<String> getAvailableActions(String instanceId) throws ProcessFlowException {
        ProcessInstance instance = getProcessInstance(instanceId);
        Process process = processRegistry.get(instance.getProcessCode());

        if (process == null || !Boolean.TRUE.equals(process.getApprovalFlow())) {
            return Collections.emptyList();
        }

        return process.getApprovalSteps().stream().filter(s -> s.getPrevStatus().equals(instance.getCurrentStatus())).map(ApprovalStep::getAction).collect(Collectors.toList());
    }

    public ProcessInstance getProcessInstance(String instanceId) throws ProcessFlowException {
        if(CommonUtils.isBlank(instanceId)) {
            throw new ProcessFlowException("Instance ID cannot be null");
        }

        return executeWithRetry(() -> {
            MongoCollection<ProcessInstance> collection =
                    database.getCollection("process_instances", ProcessInstance.class);

            ProcessInstance instance = collection
                    .find(eq("_id", new ObjectId(instanceId)))
                    .first();

            if (instance == null) {
                throw new ProcessFlowException("Process instance not found: " + instanceId);
            }
            return instance;
        }, "get process instance");
    }

    public List<ProcessInstance> getProcessInstancesByEntity(Integer status, Integer limit) {
        MongoCollection<Document> collection = database.getCollection("process_instances");
        List<ProcessInstance> instances = new ArrayList<>();

        var filter = eq("currentStatus", status);

        FindIterable<Document> results = collection.find(filter).sort(new Document("createdDate", -1));

        if (limit != null && limit > 0) {
            results.limit(limit);
        } else {
            results.limit(10);
        }

        results.forEach(doc -> {
            try {
                instances.add(objectMapper.readValue(doc.toJson(), ProcessInstance.class));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });

        return instances;
    }

    public List<ProcessInstance> queryInstances(ProcessQueryBuilder query) {
        MongoCollection<Document> collection = database.getCollection("process_instances");
        List<ProcessInstance> instances = new ArrayList<>();

        FindIterable<Document> results = collection.find(query.build()).sort(query.getSort()).skip(query.getSkip()).limit(query.getLimit());

        results.forEach(doc -> {
            try {
                instances.add(objectMapper.readValue(doc.toJson(), ProcessInstance.class));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });

        return instances;
    }

    public long countInstances(ProcessQueryBuilder query) {
        MongoCollection<Document> collection = database.getCollection("process_instances");
        return collection.countDocuments(query.build());
    }

    public String getStatusDescription(Integer statusCode) throws ProcessFlowException {
        Status status = statusRegistry.get(statusCode);
        return status != null ? status.getDescription() : "Unknown";
    }

    public ProcessInstance getCompletedProcessInstance(String instanceId) throws ProcessFlowException {
        if (CommonUtils.isBlank(instanceId)) {
            throw new ProcessFlowException("Instance ID must be non-empty");
        }

        try {
            MongoCollection<ProcessInstance> completedCollection = database.getCollection("process_instances_completed", ProcessInstance.class);
            return completedCollection.find(eq("_id", new ObjectId(instanceId))).first();
        } catch (IllegalArgumentException e) {
            // ObjectId parsing error
            throw new ProcessFlowException("Invalid instance ID format", e);
        } catch (Exception e) {
            throw new ProcessFlowException("Failed to fetch completed process instance", e);
        }
    }


    private boolean isProcessComplete(ProcessInstance instance) throws ProcessFlowException {
        Process process = processRegistry.get(instance.getProcessCode());

        if (process == null || !Boolean.TRUE.equals(process.getApprovalFlow())) {
            return false;
        }

        return instance.getCurrentLevel() >= process.getNoOfLevels();
    }

    private <T> T executeWithRetry(RetryableOperation<T> operation, String operationName) throws ProcessFlowException {

        int attempts = 0;
        Exception lastException = null;

        while (attempts < config.getRetryAttempts()) {
            try {
                return operation.execute();
            } catch (OptimisticLockException e) {
                throw e; // Don't retry optimistic lock failures
            } catch (ProcessFlowException e) {
                throw e; // Don't retry business logic errors
            } catch (Exception e) {
                lastException = e;
                attempts++;

                if (attempts < config.getRetryAttempts()) {
                    logger.warn("Retry attempt {} for {} failed: {}", attempts, operationName, e.getMessage());
                    try {
                        Thread.sleep(config.getRetryDelayMs() * attempts);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ProcessFlowException("Operation interrupted", ie);
                    }
                }
            }
        }

        throw new ProcessFlowException(String.format("Failed to %s after %d attempts", operationName, attempts), lastException);
    }

    @FunctionalInterface
    private interface RetryableOperation<T> {
        T execute() throws Exception;
    }

    @Override
    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }

        logger.info("ProcessFlowService closed");
    }
}