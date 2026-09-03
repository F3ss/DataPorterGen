function fail(message) { throw new Error("MERGE E2E verification failed: " + message); }
function canonical(value) { return EJSON.stringify(value, { relaxed: false }); }
function portable(index) {
  const copy = Object.assign({}, index);
  delete copy.v;
  delete copy.ns;
  return copy;
}

const sourceClient = new Mongo(process.env.SOURCE_URI);
const targetClient = new Mongo(process.env.TARGET_URI);
const source = sourceClient.getDB(process.env.SOURCE_DATABASE);
const target = targetClient.getDB(process.env.TARGET_DATABASE);

const expectedObjects = [
  "active_customers_view", "customers", "empty_collection", "events", "orders",
  "target_only_collection", "target_only_view"
];
const actualObjects = target.getCollectionInfos().map(info => info.name)
  .filter(name => !name.startsWith("system.")).sort();
if (canonical(actualObjects) !== canonical(expectedObjects))
  fail("target objects differ: " + canonical(actualObjects));

const sourceFirst = source.customers.findOne({ _id: ObjectId("65a000000000000000000001") });
const overwritten = target.customers.findOne({ _id: ObjectId("65a000000000000000000001") });
if (!overwritten || canonical(overwritten) !== canonical(sourceFirst))
  fail("MERGE did not overwrite the conflicting customer with the exact source BSON");
const sourceInserted = source.customers.findOne({ _id: ObjectId("65a000000000000000000002") });
const targetInserted = target.customers.findOne({ _id: ObjectId("65a000000000000000000002") });
if (canonical(sourceInserted) !== canonical(targetInserted))
  fail("missing customer was not copied with exact BSON values and field order");
if (!target.customers.findOne({ _id: ObjectId("65afffffffffffffffffffff"), targetOnly: true }))
  fail("target-only customer was removed");
if (target.customers.countDocuments({}) !== 3) fail("unexpected customer count");

for (const name of ["orders", "events", "empty_collection"]) {
  const sourceDocs = source.getCollection(name).find().sort({ _id: 1 }).toArray();
  const targetDocs = target.getCollection(name).find().sort({ _id: 1 }).toArray();
  if (canonical(sourceDocs) !== canonical(targetDocs)) fail("documents differ for " + name);
}
if (!target.target_only_collection.findOne({ retained: true }))
  fail("target-only collection/document was removed");

for (const name of ["customers", "orders"]) {
  const required = source.getCollection(name).getIndexes().filter(index => index.name !== "_id_");
  const actualByName = Object.fromEntries(target.getCollection(name).getIndexes().map(index => [index.name, index]));
  for (const expected of required) {
    if (!actualByName[expected.name]) fail("missing source index " + name + "." + expected.name);
    if (canonical(portable(expected)) !== canonical(portable(actualByName[expected.name])))
      fail("index differs " + name + "." + expected.name);
  }
}
if (!target.customers.getIndexes().some(index => index.name === "customers_target_only"))
  fail("target-only index was removed");
if (target.customers.getIndexes().filter(index => index.name !== "_id_").length !== 6)
  fail("customers should retain/create five source indexes plus one target-only index");

const sourceView = source.getCollectionInfos({ name: "active_customers_view" })[0];
const targetView = target.getCollectionInfos({ name: "active_customers_view" })[0];
if (!targetView || sourceView.options.viewOn !== targetView.options.viewOn
    || canonical(sourceView.options.pipeline) !== canonical(targetView.options.pipeline))
  fail("identical source view was not retained");
if (!target.getCollectionInfos({ name: "target_only_view" }).length)
  fail("target-only view was removed");

print("MERGE_E2E_VERIFICATION_SUCCESS source=source_catalog target=merge_catalog objects="
  + expectedObjects.length + " inserted=3 overwritten=1");
quit(0);
