function fail(message) { throw new Error("E2E verification failed: " + message); }
function canonical(value) { return EJSON.stringify(value, { relaxed: false }); }
function withoutVolatileIndexFields(index) {
  const copy = Object.assign({}, index); delete copy.v; delete copy.ns; return copy;
}
function byName(values) { return values.sort((a, b) => a.name.localeCompare(b.name)); }

const sourceClient = new Mongo(process.env.SOURCE_URI);
const targetClient = new Mongo(process.env.TARGET_URI);
const source = sourceClient.getDB(process.env.SOURCE_DATABASE);
const target = targetClient.getDB(process.env.TARGET_DATABASE);
const expectedCollections = ["active_customers_view", "customers", "empty_collection", "events", "orders"];
const expectedIndexes = {
  customers: ["customers_account_number_hashed", "customers_active_created", "customers_age_sparse", "customers_email_unique", "customers_name_partial"],
  orders: ["orders_customer_total", "orders_expires_at_ttl"],
  events: [],
  empty_collection: []
};

const targetNames = target.getCollectionInfos().map(info => info.name)
  .filter(name => !name.startsWith("system.")).sort();
if (canonical(targetNames) !== canonical(expectedCollections)) fail("target objects differ: " + canonical(targetNames));
if (targetClient.getDB("admin").adminCommand({ listDatabases: 1 }).databases.some(d => d.name === process.env.SOURCE_DATABASE))
  fail("source database name was incorrectly created on target");

for (const name of ["customers", "orders", "events", "empty_collection"]) {
  const sourceDocs = source.getCollection(name).find().sort({ _id: 1 }).toArray();
  const targetDocs = target.getCollection(name).find().sort({ _id: 1 }).toArray();
  if (canonical(sourceDocs) !== canonical(targetDocs)) fail("BSON documents differ for " + name);
  const sourceIndexes = byName(source.getCollection(name).getIndexes().filter(i => i.name !== "_id_").map(withoutVolatileIndexFields));
  const targetIndexes = byName(target.getCollection(name).getIndexes().filter(i => i.name !== "_id_").map(withoutVolatileIndexFields));
  const actualNames = targetIndexes.map(index => index.name).sort();
  if (canonical(actualNames) !== canonical(expectedIndexes[name]))
    fail("secondary index names differ for " + name + ": " + canonical(actualNames));
  if (canonical(sourceIndexes) !== canonical(targetIndexes)) fail("indexes differ for " + name);
}

if (target.empty_collection.countDocuments({}) !== 0) fail("empty_collection is not empty");
const sourceCustomers = source.getCollectionInfos({ name: "customers" })[0];
const targetCustomers = target.getCollectionInfos({ name: "customers" })[0];
if (canonical(sourceCustomers.options) !== canonical(targetCustomers.options)) fail("customer validator/options differ");
const sourceEvents = source.getCollectionInfos({ name: "events" })[0];
const targetEvents = target.getCollectionInfos({ name: "events" })[0];
if (canonical(sourceEvents.options) !== canonical(targetEvents.options)) fail("capped collection options differ");
const sourceView = source.getCollectionInfos({ name: "active_customers_view" })[0];
const targetView = target.getCollectionInfos({ name: "active_customers_view" })[0];
if (sourceView.options.viewOn !== targetView.options.viewOn || canonical(sourceView.options.pipeline) !== canonical(targetView.options.pipeline))
  fail("viewOn or pipeline differs");

print("E2E_VERIFICATION_SUCCESS source=source_catalog target=migrated_catalog objects=" + expectedCollections.length);
quit(0);
