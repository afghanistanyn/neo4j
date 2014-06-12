/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.nioneo.xa;

import static org.neo4j.helpers.collection.IteratorUtil.loop;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.neo4j.graphdb.DatabaseShutdownException;
import org.neo4j.graphdb.DependencyResolver;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.Exceptions;
import org.neo4j.helpers.Factory;
import org.neo4j.helpers.Function;
import org.neo4j.helpers.Provider;
import org.neo4j.helpers.collection.Visitor;
import org.neo4j.kernel.InternalAbstractGraphDatabase;
import org.neo4j.kernel.TransactionEventHandlers;
import org.neo4j.kernel.api.KernelAPI;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.TokenNameLookup;
import org.neo4j.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.kernel.api.index.SchemaIndexProvider;
import org.neo4j.kernel.api.labelscan.LabelScanStore;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.guard.Guard;
import org.neo4j.kernel.impl.api.ConstraintEnforcingEntityOperations;
import org.neo4j.kernel.impl.api.DataIntegrityValidatingStatementOperations;
import org.neo4j.kernel.impl.api.GuardingStatementOperations;
import org.neo4j.kernel.impl.api.Kernel;
import org.neo4j.kernel.impl.api.KernelTransactionImplementation;
import org.neo4j.kernel.impl.api.LegacyPropertyTrackers;
import org.neo4j.kernel.impl.api.LockingStatementOperations;
import org.neo4j.kernel.impl.api.SchemaStateConcern;
import org.neo4j.kernel.impl.api.SchemaWriteGuard;
import org.neo4j.kernel.impl.api.StateHandlingStatementOperations;
import org.neo4j.kernel.impl.api.StatementOperationParts;
import org.neo4j.kernel.impl.api.TransactionHeaderInformation;
import org.neo4j.kernel.impl.api.TransactionHooks;
import org.neo4j.kernel.impl.api.TransactionRepresentationCommitProcess;
import org.neo4j.kernel.impl.api.UpdateableSchemaState;
import org.neo4j.kernel.impl.api.index.IndexingService;
import org.neo4j.kernel.impl.api.scan.LabelScanStoreProvider;
import org.neo4j.kernel.impl.api.state.ConstraintIndexCreator;
import org.neo4j.kernel.impl.api.statistics.StatisticsService;
import org.neo4j.kernel.impl.api.statistics.StatisticsServiceRepository;
import org.neo4j.kernel.impl.api.store.CacheLayer;
import org.neo4j.kernel.impl.api.store.DiskLayer;
import org.neo4j.kernel.impl.api.store.PersistenceCache;
import org.neo4j.kernel.impl.api.store.SchemaCache;
import org.neo4j.kernel.impl.api.store.StoreReadLayer;
import org.neo4j.kernel.impl.cache.AutoLoadingCache;
import org.neo4j.kernel.impl.cache.BridgingCacheAccess;
import org.neo4j.kernel.impl.core.CacheAccessBackDoor;
import org.neo4j.kernel.impl.core.Caches;
import org.neo4j.kernel.impl.core.DenseNodeImpl;
import org.neo4j.kernel.impl.core.LabelTokenHolder;
import org.neo4j.kernel.impl.core.NodeImpl;
import org.neo4j.kernel.impl.core.NodeManager;
import org.neo4j.kernel.impl.core.PropertyKeyTokenHolder;
import org.neo4j.kernel.impl.core.RelationshipImpl;
import org.neo4j.kernel.impl.core.RelationshipLoader;
import org.neo4j.kernel.impl.core.RelationshipTypeTokenHolder;
import org.neo4j.kernel.impl.core.StartupStatisticsProvider;
import org.neo4j.kernel.impl.locking.LockService;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.locking.ReentrantLockService;
import org.neo4j.kernel.impl.nioneo.store.FileSystemAbstraction;
import org.neo4j.kernel.impl.nioneo.store.IndexRule;
import org.neo4j.kernel.impl.nioneo.store.InvalidRecordException;
import org.neo4j.kernel.impl.nioneo.store.NeoStore;
import org.neo4j.kernel.impl.nioneo.store.NodeRecord;
import org.neo4j.kernel.impl.nioneo.store.NodeStore;
import org.neo4j.kernel.impl.nioneo.store.RelationshipRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipStore;
import org.neo4j.kernel.impl.nioneo.store.SchemaRule;
import org.neo4j.kernel.impl.nioneo.store.SchemaStorage;
import org.neo4j.kernel.impl.nioneo.store.StoreFactory;
import org.neo4j.kernel.impl.nioneo.store.StoreId;
import org.neo4j.kernel.impl.nioneo.store.TokenStore;
import org.neo4j.kernel.impl.nioneo.store.WindowPoolStats;
import org.neo4j.kernel.impl.storemigration.StoreUpgrader;
import org.neo4j.kernel.impl.transaction.KernelHealth;
import org.neo4j.kernel.impl.transaction.RemoteTxHook;
import org.neo4j.kernel.impl.transaction.xaframework.LogEntry;
import org.neo4j.kernel.impl.transaction.xaframework.LogFile;
import org.neo4j.kernel.impl.transaction.xaframework.LogFileInformation;
import org.neo4j.kernel.impl.transaction.xaframework.LogPruneStrategies;
import org.neo4j.kernel.impl.transaction.xaframework.LogPruneStrategy;
import org.neo4j.kernel.impl.transaction.xaframework.LogRotationControl;
import org.neo4j.kernel.impl.transaction.xaframework.PhysicalLogFile;
import org.neo4j.kernel.impl.transaction.xaframework.PhysicalLogFileInformation;
import org.neo4j.kernel.impl.transaction.xaframework.PhysicalLogFiles;
import org.neo4j.kernel.impl.transaction.xaframework.PhysicalTransactionStore;
import org.neo4j.kernel.impl.transaction.xaframework.ReadableLogChannel;
import org.neo4j.kernel.impl.transaction.xaframework.TransactionMetadataCache;
import org.neo4j.kernel.impl.transaction.xaframework.TransactionMonitor;
import org.neo4j.kernel.impl.transaction.xaframework.TransactionRepresentation;
import org.neo4j.kernel.impl.transaction.xaframework.TransactionStore;
import org.neo4j.kernel.impl.transaction.xaframework.TxIdGenerator;
import org.neo4j.kernel.impl.transaction.xaframework.VersionAwareLogEntryReader;
import org.neo4j.kernel.impl.util.JobScheduler;
import org.neo4j.kernel.impl.util.StringLogger;
import org.neo4j.kernel.info.DiagnosticsExtractor;
import org.neo4j.kernel.info.DiagnosticsManager;
import org.neo4j.kernel.info.DiagnosticsPhase;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.kernel.logging.Logging;

public class NeoStoreXaDataSource implements NeoStoreProvider, Lifecycle, LogRotationControl
{
    public static final String DEFAULT_DATA_SOURCE_NAME = "nioneodb";
    private LogFile logFile;
    private TransactionStore transactionStore;

    @SuppressWarnings( "deprecation" )
    public static abstract class Configuration
    {
        public static final Setting<String> keep_logical_logs = GraphDatabaseSettings.keep_logical_logs;
        public static final Setting<Boolean> read_only = GraphDatabaseSettings.read_only;
        public static final Setting<File> store_dir = InternalAbstractGraphDatabase.Configuration.store_dir;
        public static final Setting<File> neo_store = InternalAbstractGraphDatabase.Configuration.neo_store;
    }

    private final DependencyResolver.Dependencies dependencies = new DependencyResolver.Dependencies();
    private final StringLogger msgLog;
    private final Logging logging;
    private final DependencyResolver dependencyResolver;
    private final TokenNameLookup tokenNameLookup;
    private final PropertyKeyTokenHolder propertyKeyTokenHolder;
    private final LabelTokenHolder labelTokens;
    private final RelationshipTypeTokenHolder relationshipTypeTokens;
    private final Locks locks;
    private final SchemaWriteGuard schemaWriteGuard;
    private final TransactionEventHandlers transactionEventHandlers;
    private final StoreFactory storeFactory;
    private final JobScheduler scheduler;
    private final UpdateableSchemaState updateableSchemaState;
    private final Config config;
    private final LockService lockService;
    private LifeSupport life;
    private KernelAPI kernel;
    private NeoStore neoStore;
    private IndexingService indexingService;
    private SchemaIndexProvider indexProvider;
    private IntegrityValidator integrityValidator;
    private NeoStoreFileListing fileListing;
    private File storeDir;
    private boolean readOnly;
    private CacheAccessBackDoor cacheAccess;
    private PersistenceCache persistenceCache;
    private SchemaCache schemaCache;
    private LabelScanStore labelScanStore;
    private final IndexingService.Monitor indexingServiceMonitor;
    private final FileSystemAbstraction fs;
    private final StoreUpgrader storeMigrationProcess;
    private final TransactionMonitor transactionMonitor;
    private final KernelHealth kernelHealth;
    private final RemoteTxHook remoteTxHook;
    private final TxIdGenerator txIdGenerator;
    private final TransactionHeaderInformation transactionHeaderInformation;
    private final StartupStatisticsProvider startupStatistics;
    private CacheLayer storeLayer;
    private final Caches cacheProvider;
    private final NodeManager nodeManager;

    private final AtomicInteger recoveredCount = new AtomicInteger();
    private StatementOperationParts statementOperations;
    private TransactionRepresentationCommitProcess commitProcess;
    private final Guard guard;

    private enum Diagnostics implements DiagnosticsExtractor<NeoStoreXaDataSource>
    {
        NEO_STORE_VERSIONS( "Store versions:" )
        {
            @Override
            void dump( NeoStoreXaDataSource source, StringLogger.LineLogger log )
            {
                source.neoStore.logVersions( log );
            }
        },
        NEO_STORE_ID_USAGE( "Id usage:" )
        {
            @Override
            void dump( NeoStoreXaDataSource source, StringLogger.LineLogger log )
            {
                source.neoStore.logIdUsage( log );
            }
        },
        PERSISTENCE_WINDOW_POOL_STATS( "Persistence Window Pool stats:" )
        {
            @Override
            void dump( NeoStoreXaDataSource source, StringLogger.LineLogger log )
            {
                source.neoStore.logAllWindowPoolStats( log );
            }

            @Override
            boolean applicable( DiagnosticsPhase phase )
            {
                return phase.isExplicitlyRequested();
            }
        };
        private final String message;

        private Diagnostics( String message )
        {
            this.message = message;
        }

        @Override
        public void dumpDiagnostics( final NeoStoreXaDataSource source, DiagnosticsPhase phase, StringLogger log )
        {
            if ( applicable( phase ) )
            {
                log.logLongMessage( message, new Visitor<StringLogger.LineLogger, RuntimeException>()
                {
                    @Override
                    public boolean visit( StringLogger.LineLogger logger )
                    {
                        dump( source, logger );
                        return false;
                    }
                }, true );
            }
        }

        boolean applicable( DiagnosticsPhase phase )
        {
            return phase.isInitialization() || phase.isExplicitlyRequested();
        }

        abstract void dump( NeoStoreXaDataSource source, StringLogger.LineLogger log );
    }

    /**
     * Creates a <CODE>NeoStoreXaDataSource</CODE> using configuration from
     * <CODE>params</CODE>. First the map is checked for the parameter
     * <CODE>config</CODE>.
     * If that parameter exists a config file with that value is loaded (via
     * {@link Properties#load}). Any parameter that exist in the config file
     * and in the map passed into this constructor will take the value from the
     * map.
     * <p>
     * If <CODE>config</CODE> parameter is set but file doesn't exist an
     * <CODE>IOException</CODE> is thrown. If any problem is found with that
     * configuration file or Neo4j store can't be loaded an <CODE>IOException is
     * thrown</CODE>.
     *
     * Note that the tremendous number of dependencies for this class, clearly, is an architecture smell. It is part
     * of the ongoing work on introducing the Kernel API, where components that were previously spread throughout the
     * core API are now slowly accumulating in the Kernel implementation. Over time, these components should be
     * refactored into bigger components that wrap the very granular things we depend on here.
     */
    public NeoStoreXaDataSource( Config config, StoreFactory sf, StringLogger stringLogger, JobScheduler scheduler,
                                 Logging logging, UpdateableSchemaState updateableSchemaState,
                                 TokenNameLookup tokenNameLookup, DependencyResolver dependencyResolver,
                                 PropertyKeyTokenHolder propertyKeyTokens, LabelTokenHolder labelTokens,
                                 RelationshipTypeTokenHolder relationshipTypeTokens, Locks lockManager,
                                 SchemaWriteGuard schemaWriteGuard, TransactionEventHandlers transactionEventHandlers,
                                 IndexingService.Monitor indexingServiceMonitor, FileSystemAbstraction fs,
                                 Function <NeoStore, Function<List<LogEntry>, List<LogEntry>>> translatorFactory,
                                 StoreUpgrader storeMigrationProcess, TransactionMonitor transactionMonitor,
                                 KernelHealth kernelHealth, RemoteTxHook remoteTxHook,
                                 TxIdGenerator txIdGenerator, TransactionHeaderInformation transactionHeaderInformation,
                                 StartupStatisticsProvider startupStatistics,
                                 Caches cacheProvider, NodeManager nodeManager, Guard guard )
    {
        this.config = config;
        this.tokenNameLookup = tokenNameLookup;
        this.dependencyResolver = dependencyResolver;
        this.scheduler = scheduler;
        this.logging = logging;
        this.propertyKeyTokenHolder = propertyKeyTokens;
        this.labelTokens = labelTokens;
        this.relationshipTypeTokens = relationshipTypeTokens;
        this.locks = lockManager;
        this.schemaWriteGuard = schemaWriteGuard;
        this.transactionEventHandlers = transactionEventHandlers;
        this.indexingServiceMonitor = indexingServiceMonitor;
        this.fs = fs;
        this.storeMigrationProcess = storeMigrationProcess;
        this.transactionMonitor = transactionMonitor;
        this.kernelHealth = kernelHealth;
        this.remoteTxHook = remoteTxHook;
        this.txIdGenerator = txIdGenerator;
        this.transactionHeaderInformation = transactionHeaderInformation;
        this.startupStatistics = startupStatistics;
        this.cacheProvider = cacheProvider;
        this.nodeManager = nodeManager;
        this.guard = guard;

        readOnly = config.get( Configuration.read_only );
        msgLog = stringLogger;
        this.storeFactory = sf;
        this.updateableSchemaState = updateableSchemaState;
        this.lockService = new ReentrantLockService();
    }

    @Override
    public void init()
    { // We do our own internal life management:
      // start() does life.init() and life.start(),
      // stop() does life.stop() and life.shutdown().
    }

    @Override
    public void start() throws IOException
    {
        life = new LifeSupport();
        readOnly = config.get( Configuration.read_only );
        storeDir = config.get( Configuration.store_dir );
        File store = config.get( Configuration.neo_store );
        if ( !storeFactory.storeExists() )
        {
            storeFactory.createNeoStore().close();
        }
        indexProvider = dependencyResolver.resolveDependency( SchemaIndexProvider.class,
                SchemaIndexProvider.HIGHEST_PRIORITIZED_OR_NONE );
        storeMigrationProcess.addParticipant( indexProvider.storeMigrationParticipant() );
        // TODO: Build a real provider map
        final DefaultSchemaIndexProviderMap providerMap = new DefaultSchemaIndexProviderMap( indexProvider );
        storeMigrationProcess.migrateIfNeeded( store.getParentFile() );
        neoStore = dependencies.add( storeFactory.newNeoStore( false ) );

        schemaCache = new SchemaCache( Collections.<SchemaRule>emptyList() );

        AutoLoadingCache<NodeImpl> nodeCache = new AutoLoadingCache<>(
                cacheProvider.node(), nodeLoader( neoStore.getNodeStore() ) );
        AutoLoadingCache<RelationshipImpl> relationshipCache = new AutoLoadingCache<>(
                cacheProvider.relationship(),
                relationshipLoader( neoStore.getRelationshipStore() ) );
        RelationshipLoader relationshipLoader = new RelationshipLoader( relationshipCache, new RelationshipChainLoader(
                neoStore ) );
        persistenceCache = new PersistenceCache( nodeCache, relationshipCache, nodeManager.newGraphProperties(),
                relationshipLoader, propertyKeyTokenHolder, relationshipTypeTokens, labelTokens );
        cacheAccess = new BridgingCacheAccess( schemaCache, updateableSchemaState, persistenceCache );
        try
        {
            indexingService = new IndexingService( scheduler, providerMap, new NeoStoreIndexStoreView(
                    lockService, neoStore ), tokenNameLookup, updateableSchemaState, Collections.<IndexRule>emptyList().iterator(), logging, indexingServiceMonitor ); // TODO 2.2-future What index rules should be
            integrityValidator = new IntegrityValidator( neoStore, indexingService );
            labelScanStore = dependencyResolver.resolveDependency( LabelScanStoreProvider.class,
                    LabelScanStoreProvider.HIGHEST_PRIORITIZED ).getLabelScanStore();
            fileListing = new NeoStoreFileListing( storeDir, labelScanStore, indexingService );
            Provider<NeoStore> neoStoreProvider = new Provider<NeoStore>()
            {
                @Override
                public NeoStore instance()
                {
                    return getNeoStore();
                }
            };
            storeLayer = new CacheLayer( new DiskLayer( propertyKeyTokenHolder, labelTokens, relationshipTypeTokens,
                    new SchemaStorage( neoStore.getSchemaStore() ), neoStoreProvider, indexingService ),
                    persistenceCache, indexingService, schemaCache );

            // CHANGE STARTS HERE
            final VersionAwareLogEntryReader logEntryReader = new VersionAwareLogEntryReader( CommandReaderFactory.DEFAULT );
            // Recovery process ties log and commit process together
            final Visitor<TransactionRepresentation, IOException> visitor = new Visitor<TransactionRepresentation, IOException>()
            {
                @Override
                public boolean visit( TransactionRepresentation transaction ) throws IOException
                {
                    try
                    {
                        commitProcess.commit( transaction );
                        recoveredCount.incrementAndGet();
                        return true;
                    }
                    catch ( TransactionFailureException e )
                    {
                        throw new IOException( "Unable to recover transaction " + transaction, e );
                    }
                }
            };
            Visitor<ReadableLogChannel, IOException> logFileRecoverer = new LogFileRecoverer( logEntryReader, visitor );

            LegacyPropertyTrackers legacyPropertyTrackers = new LegacyPropertyTrackers( propertyKeyTokenHolder,
                    nodeManager.getNodePropertyTrackers(), nodeManager.getRelationshipPropertyTrackers(), nodeManager );
            StatisticsService statisticsService = new StatisticsServiceRepository( fs, config, storeLayer, scheduler ).loadStatistics();
            final NeoStoreTransactionContextSupplier neoStoreTransactionContextSupplier = new NeoStoreTransactionContextSupplier( neoStore );

            final TransactionHooks hooks = new TransactionHooks();
            File directory = config.get( GraphDatabaseSettings.store_dir );
            TransactionMetadataCache transactionMetadataCache = new TransactionMetadataCache( 1000, 100_000 );
            PhysicalLogFiles logFiles = new PhysicalLogFiles( directory, PhysicalLogFile.DEFAULT_NAME, fs );
            LogFileInformation logFileInformation = new PhysicalLogFileInformation(
                    logFiles, transactionMetadataCache, fs, neoStore );
            LogPruneStrategy logPruneStrategy = LogPruneStrategies.fromConfigValue( fs, logFileInformation,
                    logFiles, neoStore, config.get( GraphDatabaseSettings.keep_logical_logs ) );
            logFile = dependencies.add( new PhysicalLogFile( fs, logFiles,
                    config.get( GraphDatabaseSettings.logical_log_rotation_threshold ), logPruneStrategy, neoStore,
                    neoStore, new PhysicalLogFile.LoggingMonitor( logging.getMessagesLog( getClass() ) ),
                    this, transactionMetadataCache, logFileRecoverer ) );
            transactionStore = dependencies.add( new PhysicalTransactionStore( logFile, txIdGenerator,
                    transactionMetadataCache, logEntryReader ) );

            this.commitProcess = new TransactionRepresentationCommitProcess( transactionStore, kernelHealth, indexingService,
                    labelScanStore, neoStore, cacheAccess, lockService, false );

            Factory<KernelTransaction> transactionFactory = new Factory<KernelTransaction>()
            {
                @Override
                public KernelTransaction newInstance()
                {

                    checkIfShutdown();
                    NeoStoreTransactionContext context = neoStoreTransactionContextSupplier.acquire();
                    Locks.Client locksClient = locks.newClient();
                    context.bind( locksClient );
                    TransactionRecordState neoStoreTransaction = new TransactionRecordState(
                            neoStore.getLastCommittingTransactionId(), neoStore, integrityValidator, context );
                    ConstraintIndexCreator constraintIndexCreator = new ConstraintIndexCreator( kernel, indexingService );
                    return new KernelTransactionImplementation( statementOperations, readOnly, schemaWriteGuard, labelScanStore,
                            indexingService, updateableSchemaState, neoStoreTransaction, providerMap, neoStore, locksClient, hooks,
                            constraintIndexCreator, transactionHeaderInformation, commitProcess, transactionMonitor, neoStore,
                            persistenceCache, storeLayer );
                }
            };

            kernel = new Kernel( statisticsService, transactionFactory, hooks );

            this.statementOperations = buildStatementOperations( storeLayer, legacyPropertyTrackers,
                    indexingService, kernel, updateableSchemaState, guard );

            life.add( logFile );
            life.add( transactionStore );
            life.add(new LifecycleAdapter()
            {
                @Override
                public void start() throws Throwable
                {
                    startupStatistics.setNumberOfRecoveredTransactions( recoveredCount.get() );
                    recoveredCount.set( 0 );

                    // Add schema rules
                    for ( SchemaRule schemaRule : loop( neoStore.getSchemaStore().loadAllSchemaRules() ) )
                    {
                        schemaCache.addSchemaRule( schemaRule );
                    }
                }
            });
            life.add(statisticsService);
            life.add(indexingService);
            life.add(labelScanStore);

            // ENDS HERE

            kernel.registerTransactionHook( transactionEventHandlers );
            life.init();
            //            // TODO 2.2-future: Why isn't this done in the init() method of the indexing service?
            //            if ( !readOnly )
            //            {
            //                neoStore.setRecoveredStatus( true );
            //                try
            //                {
            //                    indexingService.initIndexes( loadIndexRules() );
            //                    xaContainer.openLogicalLog();
            //                }
            //                finally
            //                {
            //                    neoStore.setRecoveredStatus( false );
            //                }
            //            }
            //            if ( !xaContainer.getResourceManager().hasRecoveredTransactions() )
            //            {
            //                neoStore.makeStoreOk();
            //            }
            //            else
            //            {
            //                msgLog.debug( "Waiting for TM to take care of recovered " +
            //                        "transactions." );
            //            }
            // TODO Problem here is that we don't know if recovery has been performed at this point
            // if it hasn't then no index recovery will be performed since the node store is still in
            // "not ok" state and forceGetRecord will always return place holder node records that are not in use.
            // This issue will certainly introduce index inconsistencies.
            life.start();

            neoStore.makeStoreOk();

            propertyKeyTokenHolder.addTokens( ((TokenStore<?>) neoStore.getPropertyKeyTokenStore())
                    .getTokens( Integer.MAX_VALUE ) );
            relationshipTypeTokens.addTokens( ((TokenStore<?>) neoStore.getRelationshipTypeTokenStore())
                    .getTokens( Integer.MAX_VALUE ) );
            labelTokens.addTokens( ((TokenStore<?>) neoStore.getLabelTokenStore()).getTokens( Integer.MAX_VALUE ) );
        }
        catch ( Throwable e )
        { // Something unexpected happened during startup
            try
            { // Close the neostore, so that locks are released properly
                neoStore.close();
            }
            catch ( Exception closeException )
            {
                msgLog.logMessage( "Couldn't close neostore after startup failure" );
            }
            throw Exceptions.launderedException( e );
        }
    }

    private AutoLoadingCache.Loader<RelationshipImpl> relationshipLoader( final RelationshipStore relationshipStore )
    {
        return new AutoLoadingCache.Loader<RelationshipImpl>()
        {
            @Override
            public RelationshipImpl loadById( long id )
            {
                try
                {
                    RelationshipRecord record = relationshipStore.getRecord( id );
                    return new RelationshipImpl( id, record.getFirstNode(), record.getSecondNode(), record.getType() );
                }
                catch ( InvalidRecordException e )
                {
                    return null;
                }
            }
        };
    }

    private AutoLoadingCache.Loader<NodeImpl> nodeLoader( final NodeStore nodeStore )
    {
        return new AutoLoadingCache.Loader<NodeImpl>()
        {
            @Override
            public NodeImpl loadById( long id )
            {
                try
                {
                    NodeRecord record = nodeStore.getRecord( id );
                    return record.isDense() ? new DenseNodeImpl( id ) : new NodeImpl( id );
                }
                catch ( InvalidRecordException e )
                {
                    return null;
                }
            }
        };
    }

    // TODO 2.2-future: In TransactionFactory (now gone) was (#onRecoveryComplete)
    //    forceEverything();
    //    neoStore.makeStoreOk();
    //    neoStore.setVersion( xaContainer.getLogicalLog().getHighestLogVersion() );
    public NeoStore getNeoStore()
    {
        return neoStore;
    }

    public IndexingService getIndexService()
    {
        return indexingService;
    }

    public SchemaIndexProvider getIndexProvider()
    {
        return indexProvider;
    }

    public LabelScanStore getLabelScanStore()
    {
        return labelScanStore;
    }

    public LockService getLockService()
    {
        return lockService;
    }

    @Override
    public void stop()
    {
        if ( !readOnly )
        {
            forceEverything();
        }
        life.shutdown();
        // TODO 2.2-future
        //        if ( logApplied )
        //        {
        //            neoStore.rebuildIdGenerators();
        //            logApplied = false;
        //        }
        neoStore.close();
        msgLog.info( "NeoStore closed" );
    }

    @Override
    public void forceEverything()
    {
        neoStore.flushAll();
        indexingService.flushAll();
        labelScanStore.force();
    }

    @Override
    public void shutdown()
    { // We do our own internal life management:
      // start() does life.init() and life.start(),
      // stop() does life.stop() and life.shutdown().
    }

    public StoreId getStoreId()
    {
        return neoStore.getStoreId();
    }

    public String getStoreDir()
    {
        return storeDir.getPath();
    }

    public long getCreationTime()
    {
        return neoStore.getCreationTime();
    }

    public long getRandomIdentifier()
    {
        return neoStore.getRandomNumber();
    }

    public long getCurrentLogVersion()
    {
        return neoStore.getCurrentLogVersion();
    }

    public boolean isReadOnly()
    {
        return readOnly;
    }

    public List<WindowPoolStats> getWindowPoolStats()
    {
        return neoStore.getAllWindowPoolStats();
    }

    public KernelAPI getKernel()
    {
        return kernel;
    }

    public boolean setRecovered( boolean recovered )
    {
        boolean currentValue = neoStore.isInRecoveryMode();
        neoStore.setRecoveredStatus( true );
        return currentValue;
    }

    public ResourceIterator<File> listStoreFiles() throws IOException
    {
        return fileListing.listStoreFiles();
    }

    public void registerDiagnosticsWith( DiagnosticsManager manager )
    {
        manager.registerAll( Diagnostics.class, this );
    }

    private Iterator<IndexRule> loadIndexRules()
    {
        return new SchemaStorage( neoStore.getSchemaStore() ).allIndexRules();
    }

    @Override
    public NeoStore evaluate()
    {
        return neoStore;
    }

    public StoreReadLayer getStoreLayer()
    {
        return storeLayer;
    }

    @Override
    public void awaitAllTransactionsClosed()
    {
        // TODO 2.2-future what if this will never happen?
        while ( !neoStore.closedTransactionIdIsOnParWithCommittingTransactionId() )
        {
            try
            {
                Thread.sleep( 1 );
            }
            catch ( InterruptedException e )
            {
                break;
            }
        }
    }

    public DependencyResolver getDependencyResolver()
    {
        return dependencies;
    }

    private StatementOperationParts buildStatementOperations(
            StoreReadLayer storeReadLayer, LegacyPropertyTrackers legacyPropertyTrackers,
            IndexingService indexingService, KernelAPI kernel, UpdateableSchemaState updateableSchemaState,
            Guard guard )
    {
        // Bottom layer: Read-access to committed data
        StoreReadLayer storeLayer = storeReadLayer;
        // + Transaction state handling
        StateHandlingStatementOperations stateHandlingContext = new StateHandlingStatementOperations( storeLayer,
                legacyPropertyTrackers, new ConstraintIndexCreator( kernel, indexingService ) );
        StatementOperationParts parts = new StatementOperationParts( stateHandlingContext, stateHandlingContext,
                stateHandlingContext, stateHandlingContext, stateHandlingContext, stateHandlingContext,
                new SchemaStateConcern( updateableSchemaState ), null, null, null );
        // + Constraints
        ConstraintEnforcingEntityOperations constraintEnforcingEntityOperations = new ConstraintEnforcingEntityOperations(
                parts.entityWriteOperations(), parts.entityReadOperations(), parts.schemaReadOperations() );
        // + Data integrity
        DataIntegrityValidatingStatementOperations dataIntegrityContext = new DataIntegrityValidatingStatementOperations(
                parts.keyWriteOperations(), parts.schemaReadOperations(), parts.schemaWriteOperations() );
        parts = parts.override( null, dataIntegrityContext, constraintEnforcingEntityOperations,
                constraintEnforcingEntityOperations, null, dataIntegrityContext, null, null, null, null );
        // + Locking
        LockingStatementOperations lockingContext = new LockingStatementOperations( parts.entityReadOperations(),
                parts.entityWriteOperations(), parts.schemaReadOperations(), parts.schemaWriteOperations(),
                parts.schemaStateOperations() );
        parts = parts.override( null, null, null, lockingContext, lockingContext, lockingContext, lockingContext,
                lockingContext, null, null );
        // + Guard
        if ( guard != null )
        {
            GuardingStatementOperations guardingOperations = new GuardingStatementOperations(
                    parts.entityWriteOperations(), parts.entityReadOperations(), guard );
            parts = parts.override( null, null, guardingOperations, guardingOperations, null, null, null, null, null, null );
        }

        return parts;
    }

    private void checkIfShutdown()
    {
        if ( !life.isRunning() )
        {
            throw new DatabaseShutdownException();
        }
    }

}
