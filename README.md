# TF Reader Backend

## MongoDB

The application uses MongoDB database `tnfreader`.

### Shared MongoDB Atlas

Set the Atlas connection string in your local `.env` file:

`MONGODB_URI=<your-mongodb-atlas-connection-string>`

Never commit `.env` or put database credentials in source-controlled files.

When using Atlas, keep the demo seeder disabled. The seeder is restricted to local MongoDB hosts because reset seeding can delete seeded data.

### Local MongoDB

Start local MongoDB:

`docker compose up -d mongodb`

Use this in `.env`:

`MONGODB_URI=mongodb://root:secret@localhost:27017/tnfreader?authSource=admin`

To load the 23-document demo dataset into local MongoDB:

`./mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.jvmArguments="-Dtnf.seed.enabled=true"`

The seeder allows only these local hosts:

- `localhost`
- `127.0.0.1`
- `::1`
- `mongo`

It refuses non-local MongoDB hosts. Do not add the Atlas hostname to `tnf.seed.allowed-hosts`.

### Switching Between Atlas and Local

Change only `MONGODB_URI` in `.env`.

- **Atlas:** use the Atlas connection string and keep seeding disabled.
- **Local:** use the Docker MongoDB connection string and enable the seeder when demo data needs to be loaded.
