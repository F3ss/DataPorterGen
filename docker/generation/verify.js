function fail(message) { throw new Error("Generation E2E verification failed: " + message); }
function canonical(value) { return EJSON.stringify(value, { relaxed: false }); }
function equal(actual, expected, message) {
  if (canonical(actual) !== canonical(expected)) fail(message + ": " + canonical(actual));
}
function indexNames(collection) { return collection.getIndexes().map(index => index.name).sort(); }

const client = new Mongo(process.env.MONGODB_URI);
const catalog = client.getDB(process.env.DATABASE);
const names = catalog.getCollectionInfos().map(info => info.name)
  .filter(name => !name.startsWith("system.")).sort();
equal(names, ["customers", "orders"], "database objects differ");

if (catalog.customers.countDocuments({}) !== 6) fail("customers count is not 6");
if (catalog.orders.countDocuments({}) !== 4) fail("orders count is not 4");

const sourceCustomers = catalog.customers.find({ generated: { $ne: true } }).sort({ _id: 1 }).toArray();
equal(sourceCustomers.map(item => item.staticValue), ["A", "B", "EXCLUDED"], "source customer templates changed");
equal(sourceCustomers.map(item => item.ordinal), [NumberLong(1), NumberLong(2), NumberLong(3)], "source customer ordinals changed");

const customers = catalog.customers.find({ generated: true }).sort({ ordinal: 1 }).toArray();
if (customers.length !== 3) fail("generated customer count is not 3");
equal(customers.map(item => item.staticValue), ["A", "B", "A"], "template cycling differs");
if (customers.some(item => item.staticValue === "EXCLUDED")) fail("filtered customer template was generated");
equal(customers.map(item => item.ordinal), [NumberLong(100), NumberLong(101), NumberLong(102)], "customer sequence differs");
if (!customers.every(item => typeof item.code === "string" && /^[0-9A-Z]{6}$/.test(item.code)
    && item.code.startsWith("00") && parseInt(item.code, 36) >= 0 && parseInt(item.code, 36) < 392000))
  fail("generated customer base-36 code is outside [0,392000) or has wrong width");
if (!customers.every(item => canonical(item._id).startsWith('{"$oid":'))) fail("generated customer _id is not ObjectId");
if (new Set(customers.map(item => item._id.toString())).size !== 3) fail("generated customer _id values are not unique");
if (!customers.every(item => canonical(item.operationDate) === canonical(ISODate("2026-09-02T10:20:30.123Z"))))
  fail("customer shared BSON date differs");
if (!customers.every(item => item.operationText === "2026-09-02T10:20:30.123Z"))
  fail("customer shared formatted date differs");

const sourceOrders = catalog.orders.find({ generated: { $ne: true } }).toArray();
if (sourceOrders.length !== 1 || sourceOrders[0]._id !== "template-order"
    || sourceOrders[0].staticValue !== "ORDER_TEMPLATE") fail("source order template changed");

const orders = catalog.orders.find({ generated: true }).sort({ sequence: 1 }).toArray();
if (orders.length !== 3) fail("generated order count is not 3");
equal(orders.map(item => item.sequence), [NumberLong(11), NumberLong(12), NumberLong(13)], "AUTO_AFTER_TARGET_MAX differs");
equal(orders.map(item => item._id), ["ORD-11", "ORD-12", "ORD-13"], "generated order ids differ");
equal(orders.map(item => item.staticValue), ["ORDER_TEMPLATE", "ORDER_TEMPLATE", "ORDER_TEMPLATE"], "order template fields differ");
for (let i = 0; i < orders.length; i++) {
  if (canonical(orders[i].customerId) !== canonical(customers[i]._id))
    fail("cross-collection customerId reference differs at iteration " + i);
}
if (!orders.every(item => canonical(item.operationDate) === canonical(ISODate("2026-09-02T10:20:30.123Z"))))
  fail("order shared BSON date differs");
if (!orders.every(item => item.legacyDate === "1260902")) fail("order shared legacy date differs");

equal(indexNames(catalog.customers), ["_id_", "customers_ordinal_unique"], "customer indexes changed");
equal(indexNames(catalog.orders), ["_id_", "orders_customer_unique", "orders_sequence_unique"], "order indexes changed");

print("GENERATION_E2E_VERIFICATION_SUCCESS database=generation_catalog generatedCustomers=3 generatedOrders=3");
quit(0);
