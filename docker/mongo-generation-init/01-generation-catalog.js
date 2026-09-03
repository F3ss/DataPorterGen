const catalog = db.getSiblingDB("generation_catalog");
const names = catalog.getCollectionInfos().map(info => info.name);

if (!names.includes("customers")) catalog.createCollection("customers");
if (!names.includes("orders")) catalog.createCollection("orders");

catalog.customers.replaceOne({ _id: ObjectId("65d000000000000000000001") }, {
  _id: ObjectId("65d000000000000000000001"), ordinal: NumberLong(1),
  staticValue: "A", templateEnabled: true, profile: { source: "template-a" }
}, { upsert: true });
catalog.customers.replaceOne({ _id: ObjectId("65d000000000000000000002") }, {
  _id: ObjectId("65d000000000000000000002"), ordinal: NumberLong(2),
  staticValue: "B", templateEnabled: true, profile: { source: "template-b" }
}, { upsert: true });
catalog.customers.replaceOne({ _id: ObjectId("65d000000000000000000003") }, {
  _id: ObjectId("65d000000000000000000003"), ordinal: NumberLong(3),
  staticValue: "EXCLUDED", templateEnabled: false, profile: { source: "template-excluded" }
}, { upsert: true });

catalog.orders.replaceOne({ _id: "template-order" }, {
  _id: "template-order", sequence: NumberLong(10),
  customerId: ObjectId("65d000000000000000000001"), staticValue: "ORDER_TEMPLATE"
}, { upsert: true });

catalog.customers.createIndex({ ordinal: 1 }, { unique: true, name: "customers_ordinal_unique" });
catalog.orders.createIndex({ sequence: 1 }, { unique: true, name: "orders_sequence_unique" });
catalog.orders.createIndex({ customerId: 1 }, { unique: true, name: "orders_customer_unique" });

print("GENERATION_SOURCE_INIT_OK database=generation_catalog");
