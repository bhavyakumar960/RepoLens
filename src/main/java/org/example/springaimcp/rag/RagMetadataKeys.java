package org.example.springaimcp.rag;

public final class RagMetadataKeys {

    private RagMetadataKeys() {
    }

    public static final String SOURCE = "source";
    public static final String LANGUAGE = "language";
    public static final String PACKAGE = "package";
    public static final String FILE_NAME = "file_name";
    public static final String REPO = "repo";
    public static final String MODULE = "module";
    public static final String SERVICE = "service";
    public static final String BRANCH = "branch";
    public static final String FILE_PATH = "file_path";
    public static final String COMMIT_SHA = "commit_sha";
    public static final String CLASS_NAME = "class_name";
    public static final String MEMBER_NAME = "member_name";
    public static final String MEMBER_TYPE = "member_type";
    public static final String SIGNATURE = "signature";
    public static final String CHUNK_INDEX = "chunk_index";
    public static final String TOTAL_CHUNKS = "total_chunks";
    public static final String PARENT_DOCUMENT_ID = "parent_document_id";
    public static final String FILE_HASH = "file_hash";
    public static final String CHUNK_HASH = "chunk_hash";
    public static final String EMBEDDING_MODEL = "embedding_model";
    public static final String RERANK_SCORE = "rerank_score";
    
    // Line information for source location
    public static final String START_LINE = "start_line";
    public static final String END_LINE = "end_line";
    
    // Document/chunk type classification
    public static final String DOCUMENT_TYPE = "document_type";
    public static final String CHUNK_TYPE = "chunk_type";
    
    // API metadata for controller methods
    public static final String HTTP_METHOD = "http_method";
    public static final String ENDPOINT_PATH = "endpoint_path";
    public static final String REQUEST_HEADERS = "request_headers";
    public static final String REQUEST_PARAMS = "request_params";
    public static final String REQUEST_BODY_TYPE = "request_body_type";
    public static final String RESPONSE_TYPE = "response_type";
    
    // Dependency metadata
    public static final String IMPORTS = "imports";
    public static final String INJECTED_DEPENDENCIES = "injected_dependencies";
    public static final String CALLED_METHODS = "called_methods";
    public static final String CALLED_CLASSES = "called_classes";
    public static final String ANNOTATIONS = "annotations";
}
