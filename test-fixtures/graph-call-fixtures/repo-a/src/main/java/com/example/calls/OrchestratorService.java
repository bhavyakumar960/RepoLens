package com.example.calls;

public class OrchestratorService extends BaseService {
    private final DependencyService dependencyService;
    private final Handler handler;
    private final MultiHandler multiHandler;

    public OrchestratorService(DependencyService dependencyService, Handler handler, MultiHandler multiHandler) {
        this.dependencyService = dependencyService;
        this.handler = handler;
        this.multiHandler = multiHandler;
    }

    public void orchestrate() {
        helper();
        recursive();
        dependencyService.process("alpha");
        dependencyService.process(10);
        handler.handle("beta");
        multiHandler.handle("gamma");
        super.audit("delta");
        Utility.validate("ok");
        ChainFactory.create(dependencyService).finalizeCall();
    }

    private void helper() {
        recursive();
    }

    private void recursive() {
        recursive();
    }
}
