/**
 * Semantic search over the artifacts IPED already interprets: extracted text, OCR, transcripts,
 * chat messages assembled into conversation sequences, images and video frames.
 *
 * <p>
 * This module is purely additive. It does <b>not</b> modify any class of {@code iped-engine},
 * {@code iped-api} or any other module: it contributes two new processing tasks with their own
 * {@link iped.configuration.Configurable}, its own Lucene indexes under
 * {@code <case>/iped/semantic/}, and the single searcher that both the analysis UI and the MCP
 * server call. The case's main index ({@code <case>/iped/index/}) never receives a field, a
 * document or an analysis change from here.
 * </p>
 *
 * <p>
 * Packages:
 * </p>
 * <ul>
 * <li>{@code config} - {@code SemanticSearchConfig}, read from {@code conf/SemanticSearchConfig.txt}</li>
 * <li>{@code model} - embedders (local ONNX Runtime, remote), identity and fingerprint</li>
 * <li>{@code prep} - passage chunking, chat sequences, video mosaic splitting</li>
 * <li>{@code store} - on-case storage: semantic indexes, build record, staging area</li>
 * <li>{@code search} - the one searcher, coverage, consolidation, hybrid fusion, context</li>
 * <li>{@code task} - the processing tasks and start-up source validation</li>
 * </ul>
 */
package iped.semantic;
