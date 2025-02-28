/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.license;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.AcknowledgedTransportMasterNodeAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.protocol.xpack.license.DeleteLicenseRequest;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

public class TransportDeleteLicenseAction extends AcknowledgedTransportMasterNodeAction<DeleteLicenseRequest> {

    private final ClusterStateLicenseService clusterStateLicenseService;

    @Inject
    public TransportDeleteLicenseAction(
        TransportService transportService,
        ClusterService clusterService,
        ClusterStateLicenseService clusterStateLicenseService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver
    ) {
        super(
            DeleteLicenseAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            DeleteLicenseRequest::new,
            indexNameExpressionResolver,
            ThreadPool.Names.MANAGEMENT
        );
        this.clusterStateLicenseService = clusterStateLicenseService;
    }

    @Override
    protected ClusterBlockException checkBlock(DeleteLicenseRequest request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }

    @Override
    protected void masterOperation(
        Task task,
        final DeleteLicenseRequest request,
        ClusterState state,
        final ActionListener<AcknowledgedResponse> listener
    ) throws ElasticsearchException {
        clusterStateLicenseService.removeLicense(
            listener.delegateFailure(
                (l, postStartBasicResponse) -> l.onResponse(AcknowledgedResponse.of(postStartBasicResponse.isAcknowledged()))
            )
        );
    }
}
