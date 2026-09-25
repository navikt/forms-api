CREATE TABLE form_clear_job (
    id UUID PRIMARY KEY,
    status VARCHAR(16) NOT NULL CHECK (status IN ('pending', 'running', 'completed', 'failed')),
    keep_set JSONB NOT NULL,
    target_paths JSONB NOT NULL,
    owner VARCHAR(128),
    lease_until TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    finished_at TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX one_active_form_clear_job ON form_clear_job ((true))
    WHERE status IN ('pending', 'running');

CREATE TABLE form_clear_job_item (
    job_id UUID NOT NULL REFERENCES form_clear_job(id),
    path VARCHAR(24) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('deleted', 'kept', 'failed')),
    error TEXT,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, path)
);
