# schedule-backend

## Build

Run tests:

```powershell
mvn test
```

Run the application:

```powershell
mvn spring-boot:run
```

Run the application on a different port when `8081` is already in use:

```powershell
$env:SERVER_PORT=8082
mvn spring-boot:run
```